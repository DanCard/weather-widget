package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate
import java.time.MonthDay

/**
 * Synthesizes climate-normal fallback rows for future dates beyond real forecast coverage, at read
 * time — mirrors the desktop app's `DesktopWeatherRepository.appendClimateNormalGaps` via the shared
 * `ClimateNormals.fillGaps`. Rows are never persisted; see
 * plans/260705-generic-gap-read-time-generation.md. Cache-only: reads the 12 cached monthly-mean
 * rows and never triggers a network fetch — an uncached location just yields no fallback rows until
 * a real fetch (elsewhere) has warmed the cache.
 */
class ClimateGapFiller(private val climateNormalDao: ClimateNormalDao) {

    suspend fun cachedNormalsByMonthDay(latitude: Double, longitude: Double): Map<MonthDay, Pair<Float, Float>> {
        val locationKey = ClimateNormals.locationKey(latitude, longitude)
        val cached = climateNormalDao.getNormalsForLocation(locationKey)
        if (cached.isEmpty()) return emptyMap()
        val monthlyHigh = cached.associate { it.monthDay.take(2).toInt() to it.highTemp }
        val monthlyLow = cached.associate { it.monthDay.take(2).toInt() to it.lowTemp }
        return ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
    }

    suspend fun gapRows(
        latitude: Double,
        longitude: Double,
        coveredDates: Set<LocalDate>,
        today: LocalDate,
        horizonDays: Long,
    ): List<ForecastEntity> {
        val normals = cachedNormalsByMonthDay(latitude, longitude)
        return ClimateNormals.fillGaps(coveredDates, normals, today, horizonDays).map { gap ->
            val epochMs = gap.date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
            ForecastEntity(
                targetDate = epochMs,
                dateOfPrediction = epochMs,
                locationLat = LocationMatch.quantize(latitude),
                locationLon = LocationMatch.quantize(longitude),
                highTemp = gap.highTemp,
                lowTemp = gap.lowTemp,
                condition = "Historical Avg",
                isClimateNormal = true,
                source = WeatherSource.GENERIC_GAP.id,
                fetchedAt = 0L,
                batchFetchedAt = 0L,
            )
        }
    }

    /**
     * Appends gap rows to [rows] for dates beyond real coverage, out to today+[horizonDays].
     * Covered dates come from [coveredDates] (see its doc); no real rows -> fill starts at [today].
     * `locationName` is derived from [rows] since callers here don't otherwise carry it.
     */
    suspend fun appendGaps(
        rows: List<ForecastEntity>,
        latitude: Double,
        longitude: Double,
        today: LocalDate,
        horizonDays: Long,
    ): List<ForecastEntity> {
        val covered = coveredDates(rows, today, horizonDays)
        val gaps = gapRows(latitude, longitude, covered, today, horizonDays)
        return if (gaps.isEmpty()) rows else rows + gaps
    }

    /** Adds gap rows to [snapshots] only under future date keys absent from the map. */
    suspend fun appendGapsToSnapshots(
        snapshots: Map<LocalDate, List<ForecastEntity>>,
        latitude: Double,
        longitude: Double,
        today: LocalDate,
        horizonDays: Long,
    ): Map<LocalDate, List<ForecastEntity>> {
        val covered = coveredDates(snapshots.values.flatten(), today, horizonDays)
        val gaps = gapRows(latitude, longitude, covered, today, horizonDays)
        if (gaps.isEmpty()) return snapshots
        val result = snapshots.toMutableMap()
        for (gap in gaps) {
            val date = LocalDate.ofEpochDay(gap.targetDate / WidgetConstants.MS_IN_A_DAY)
            if (date !in result) result[date] = listOf(gap)
        }
        return result
    }

    companion object {
        /**
         * Covered = dates <= the min-across-real-sources max targetDate (assumes each source's daily
         * coverage is contiguous from today — so short-coverage sources like a 7-day NWS window don't
         * make a 16-day Open-Meteo window's tail look "covered"), plus any date already carrying a
         * (leftover-persisted, pre-upgrade) gap row, so repeated calls stay idempotent. No real rows
         * -> empty (fill starts at [today]). Extracted as a pure, DAO-free function for direct
         * unit tests.
         */
        internal fun coveredDates(rows: List<ForecastEntity>, today: LocalDate, horizonDays: Long): Set<LocalDate> {
            val realRows = rows.filter { it.source != WeatherSource.GENERIC_GAP.id }
            val maxDatePerSource = realRows.groupBy { it.source }
                .mapValues { (_, sourceRows) ->
                    LocalDate.ofEpochDay(sourceRows.maxOf { it.targetDate } / WidgetConstants.MS_IN_A_DAY)
                }
            val cutoff = maxDatePerSource.values.minOrNull()
            val end = today.plusDays(horizonDays)

            val coveredByCutoff = if (cutoff != null) {
                generateSequence(today) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(cutoff) && !it.isAfter(end) }
                    .toSet()
            } else {
                emptySet()
            }

            val existingGapDates = rows.filter { it.source == WeatherSource.GENERIC_GAP.id }
                .map { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                .toSet()

            return coveredByCutoff + existingGapDates
        }
    }
}
