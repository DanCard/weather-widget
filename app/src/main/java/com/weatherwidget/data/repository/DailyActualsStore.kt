package com.weatherwidget.data.repository

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.handlers.GraphDataLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DailyActualsStore"
private const val DAYTIME_COVERAGE_HOUR = 14

@VisibleForTesting
internal fun pastDayLacksAfternoonCoverage(
    obsTimestampsMs: List<Long>,
    date: LocalDate,
    zone: ZoneId,
    today: LocalDate,
    daytimeHour: Int = DAYTIME_COVERAGE_HOUR,
): Boolean {
    if (!date.isBefore(today) || obsTimestampsMs.isEmpty()) return false
    return obsTimestampsMs.none { ms ->
        Instant.ofEpochMilli(ms).atZone(zone).hour >= daytimeHour
    }
}

@Singleton
class DailyActualsStore @Inject constructor(
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val personalStationWeightProvider: PersonalStationWeightProvider,
) {
    suspend fun getDailyActualsWithLiveToday(
        latitude: Double,
        longitude: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
    ): DailyActualsBySource {
        val activeSources = activeSourceList
            .map(WeatherSource::fromId)
            .map { it.id }
            .toSet()
        if (activeSources.isEmpty()) return emptyMap()
        // Today's live blend mixes stored OBSERVATIONS, so it stays capability-gated: a source
        // with no actuals product (Open-Meteo, Silurian) must never contribute a "current actual".
        val actualsCapableSources = activeSourceList
            .map(WeatherSource::fromId)
            .filter { it.supportsTemperatureActuals }
            .map { it.id }
            .toSet()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val startDate = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        // Past rows are included for every active source: forecast-only rows (null computed*)
        // carry the frozen forecast the history columns label. Readers must use
        // computedTemp ?: forecastTemp, never assume computed* is non-null.
        val pastExtremes = dailyHistoryDao
            .getExtremesInRange(startDate, endDate, latitude, longitude)
            .filter { it.source in activeSources }
        val pastActuals = ObservationResolver.extremesToDailyActualsBySource(pastExtremes, latitude, longitude)

        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val contextObs = observationDao
            .getObservationsInRange(
                todayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS,
                tomorrowMs,
                latitude,
                longitude,
            )
            .filter { it.stationId != "NWS_BLEND" && it.api in actualsCapableSources }
        val activeHourly = hourlyForecasts.filter { it.source in actualsCapableSources }
        val todayObs = contextObs.filter { it.timestamp in todayStartMs until tomorrowMs }

        val todayBlendedActuals = ObservationResolver.aggregateObservationsToDailyBySource(
            observations = contextObs,
            hourlyForecasts = activeHourly,
            locationLat = latitude,
            locationLon = longitude,
            personalStationWeight = personalStationWeightProvider.currentWeight(),
        )

        val obsSpanSummary =
            if (todayObs.isEmpty()) {
                "none"
            } else {
                val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                val firstLocal = Instant.ofEpochMilli(todayObs.minOf { it.timestamp })
                    .atZone(zone)
                    .toLocalDateTime()
                    .format(formatter)
                val lastLocal = Instant.ofEpochMilli(todayObs.maxOf { it.timestamp })
                    .atZone(zone)
                    .toLocalDateTime()
                    .format(formatter)
                "$firstLocal..$lastLocal"
            }
        val liveSummary = todayBlendedActuals
            .toSortedMap()
            .entries
            .joinToString("; ") { (source, actualsByDate) ->
                val actual = actualsByDate[today]
                val stationCount = todayObs.count { it.api == source }
                "$source[blendedHigh=${actual?.computedHighTemp},blendedLow=${actual?.computedLowTemp},rows=$stationCount]"
            }
            .ifEmpty { "none" }
        Log.d(
            TAG,
            "getDailyActualsWithLiveToday: date=$today lat=$latitude lon=$longitude " +
                "todayObsRows=${todayObs.size} span=$obsSpanSummary live=[$liveSummary]",
        )

        return ObservationResolver.mergeDailyActualsBySource(
            primary = pastActuals,
            secondary = todayBlendedActuals,
        )
    }

    internal suspend fun recomputeDailyExtremesFromStoredObservations(
        latitude: Double,
        longitude: Double,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val cutoffDate = LocalDate.now().minusDays(9)
        var current = startDate
        while (!current.isAfter(endDateInclusive)) {
            if (!current.isBefore(cutoffDate)) {
                recomputeDailyExtremesForDay(latitude, longitude, current, hourlyForecasts)
            } else {
                Log.d(TAG, "recomputeDailyExtremesFromStoredObservations: skipping pruned date $current")
            }
            current = current.plusDays(1)
        }
    }

    internal suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val zone = ZoneId.systemDefault()
        val dateMillis = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val startTs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val contextStartTs = startTs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextEndTs = endTs + ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextObs = observationDao.getObservationsInRange(
            contextStartTs,
            contextEndTs,
            latitude,
            longitude,
        )
        val dayObs = contextObs.filter { it.timestamp in startTs until endTs }
        if (dayObs.isEmpty()) return

        val effectiveHourly = hourlyForecasts.ifEmpty {
            GraphDataLoader.unifyToNearestSite(
                hourlyForecastDao.getHourlyForecasts(
                    contextStartTs,
                    contextEndTs,
                    latitude,
                    longitude,
                ),
                latitude,
                longitude,
            )
        }
        val newExtremes = ObservationResolver
            .computeDailyExtremes(
                contextObs,
                effectiveHourly,
                latitude,
                longitude,
                personalStationWeightProvider.currentWeight(),
            )
            .filter { it.date == dateMillis }
        // NOTE: apiHighTemp/apiLowTemp are deliberately NOT derived here. The stored observation
        // pool is a mix of NWS API rows and Synoptic rows from the prefer-newest latest path, and
        // its API subset is too thinly sampled to carry a daily peak (measured at KNUQ: 17-24 of
        // the endpoint's 72 readings, under-reporting two days' maxima by 1.8 °F). NWS daily
        // extremes come from a dedicated complete pull instead — see NwsApiDailyActualsFetcher.
        // persistExtremes preserves whatever that writer stored.

        logBlendBreakdown(date, dayObs, newExtremes, latitude, longitude)
        logExtremaWindowDiagnostic(
            date = date,
            zone = zone,
            startTs = startTs,
            endTs = endTs,
            dayObs = dayObs,
            effectiveHourly = effectiveHourly,
            latitude = latitude,
            longitude = longitude,
        )

        persistExtremes(date, dateMillis, newExtremes, latitude, longitude)
    }

    private suspend fun logBlendBreakdown(
        date: LocalDate,
        dayObservations: List<com.weatherwidget.data.local.ObservationEntity>,
        newExtremes: List<DailyHistoryEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        val perSourceBreakdown = dayObservations
            .groupBy { it.api }
            .mapValues { (_, sourceObservations) ->
                sourceObservations.groupBy { it.stationId }.entries.joinToString(",") { (stationId, observations) ->
                    val distance = observations.first().distanceKm
                    val high = observations.maxOf { it.temperature }
                    val low = observations.minOf { it.temperature }
                    "$stationId(d=${"%.2f".format(distance)}km,hi=$high,lo=$low,n=${observations.size})"
                }
            }
        newExtremes.forEach { new ->
            appLogDao.log(
                "DAILY_HISTORY_BLEND",
                "date=$date src=${new.source} computed_hi=${new.computedHighTemp} computed_lo=${new.computedLowTemp} " +
                    "stations=[${perSourceBreakdown[new.source] ?: "n/a"}] " +
                    "userLat=$latitude userLon=$longitude",
                "VERBOSE",
            )
        }
    }

    /**
     * Reads the current rows **here**, immediately before merging and writing, rather than taking
     * a snapshot from the caller.
     *
     * The caller does a network-free but non-trivial amount of work between its own reads and this
     * write — observation queries, the IDW blend, two diagnostic log passes. A concurrent
     * [persistNwsDailyActuals] landing in that window used to be silently clobbered by the stale
     * snapshot. Observed on the Pixel 2026-08-08: the station pull wrote all six dates with
     * provenance at 12:59:30.115, then a recompute overwrote 2026-08-06 with a snapshot in which
     * `actualsSource` was still null, erasing it. 08-07 survived only on interleaving luck. Because
     * the freeze guard reads that same field, the race also defeats the guard on the very cycle
     * that establishes it — both dates' blends moved despite being pull-derived.
     *
     * This used to only shrink the window to the merge loop. The write is now an optimistic
     * conditional UPDATE ([DailyHistoryDao.updateBlendIfUnchanged]) that sets ONLY the columns the
     * recompute owns and is keyed on the row's `updatedAt` still matching what we read — so a
     * concurrent [persistNwsDailyActuals] can no longer have its provenance clobbered by a stale
     * snapshot, and a conflicting write is detected and skipped. (Residual, accepted risk: `updatedAt`
     * is millisecond precision, so two writes in the same millisecond could theoretically collide.)
     */
    private suspend fun persistExtremes(
        date: LocalDate,
        dateMillis: Long,
        newExtremes: List<DailyHistoryEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        val existingHistory = dailyHistoryDao
            .getExtremesInRange(dateMillis, dateMillis, latitude, longitude)
            .groupBy { it.source }
        val toInsert = mutableListOf<DailyHistoryEntity>()
        newExtremes.forEach { new ->
            val fragments = existingHistory[new.source].orEmpty()
            if (fragments.isEmpty()) {
                toInsert.add(new.copy(lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue))
                return@forEach
            }
            var changedAny = false
            fragments.forEach { existing ->
                // Build the row we would write, then compare it whole. Enumerating the fields to
                // compare is how precip-only deltas were silently dropped before (see
                // recomputeDailyExtremesForDay's precip gate); comparing the merged candidate
                // against `existing` cannot go stale when a column is added.
                // A past day whose blend came from the NWS station pull is frozen. Without this the
                // ordinary recompute — which runs on widget loads and history-screen opens — would
                // immediately overwrite the API-derived blend with one rebuilt from the stored
                // (part-Synoptic, thinner) pool. Today's row is never frozen; its blend must stay
                // live. CACHED_OBSERVATIONS rows are NOT frozen: their blend already comes from the
                // stored pool, so the recompute is its rightful owner and can keep improving it as
                // observations backfill.
                val freezeBlend = DailyActualsSource.fromStored(existing.actualsSource) ==
                    DailyActualsSource.NWS_STATION_PULL && date.isBefore(LocalDate.now())
                // Built from `existing`, enumerating only the fields THIS writer owns.
                //
                // It used to be built from `new` — a freshly constructed entity from
                // ObservationResolver.computeDailyExtremes — with a list of fields to take back
                // from `existing`. That inverts the safe default: every column added later
                // defaults to null in `new` and is silently dropped unless someone remembers to
                // extend the list. `actualsSource` was dropped exactly that way the day it was
                // added, which also disabled the freeze guard that reads it. Building from
                // `existing` means an unknown column is preserved by construction.
                val merged = existing.copy(
                    computedHighTemp = if (freezeBlend) existing.computedHighTemp else new.computedHighTemp,
                    computedLowTemp = if (freezeBlend) existing.computedLowTemp else new.computedLowTemp,
                    condition = new.condition,
                    precipAmountMm = new.precipAmountMm,
                    precipDayMm = new.precipDayMm,
                    precipNightMm = new.precipNightMm,
                    lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue,
                )
                if (merged.copy(lastWriter = existing.lastWriter) != existing) {
                    changedAny = true
                    appLogDao.log(
                        "DAILY_HISTORY_OVERWRITE",
                        "date=$date src=${new.source} at=${existing.locationLat},${existing.locationLon} " +
                            "high=${existing.computedHighTemp}->${new.computedHighTemp} low=${existing.computedLowTemp}->${new.computedLowTemp} " +
                            "precip=${existing.precipAmountMm}->${new.precipAmountMm} " +
                            "apiHigh=${existing.apiHighTemp}->${merged.apiHighTemp} station=${merged.apiStationId}",
                        "DEBUG",
                    )
                    updateBlendRow(merged, existing, new.updatedAt, date)
                }
            }
            if (!changedAny) {
                appLogDao.log(
                    "DAILY_HISTORY_STABLE",
                    "date=$date src=${new.source} high=${new.computedHighTemp} low=${new.computedLowTemp} fragments=${fragments.size}",
                    "DEBUG",
                )
            }
        }

        if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
    }

    /**
     * Writes a recomputed blend for ONE existing fragment via an optimistic conditional UPDATE
     * (full PK + the `updatedAt` we read). Only the fields the blend recompute owns are set, so a
     * concurrent writer that already bumped provenance fields (`actualsSource`, `apiHighTemp`, …)
     * can never be clobbered. 0 affected rows means that writer landed between our read and write;
     * the row is left alone and the next recompute will pick up the change.
     */
    private suspend fun updateBlendRow(
        merged: DailyHistoryEntity,
        existing: DailyHistoryEntity,
        newUpdatedAt: Long,
        date: LocalDate,
    ) {
        val updated = dailyHistoryDao.updateBlendIfUnchanged(
            date = merged.date,
            source = merged.source,
            locationLat = merged.locationLat,
            locationLon = merged.locationLon,
            computedHighTemp = merged.computedHighTemp,
            computedLowTemp = merged.computedLowTemp,
            condition = merged.condition,
            precipAmountMm = merged.precipAmountMm,
            precipDayMm = merged.precipDayMm,
            precipNightMm = merged.precipNightMm,
            lastWriter = merged.lastWriter,
            updatedAt = newUpdatedAt,
            expectedUpdatedAt = existing.updatedAt,
        )
        if (updated == 0) {
            appLogDao.log(
                "DAILY_HISTORY_RACE",
                "date=$date src=${merged.source} at=${merged.locationLat},${merged.locationLon} " +
                    "expectedUpdatedAt=${existing.updatedAt} — skipped, row changed concurrently",
                "WARN",
            )
        }
    }

    internal suspend fun incompletelyCoveredPastDates(
        dayKeyEpochs: Set<Long>,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        today: LocalDate,
    ): Set<Long> =
        dayKeyEpochs.filterTo(mutableSetOf()) { dayKeyEpoch ->
            val date = LocalDate.ofEpochDay(dayKeyEpoch / WidgetConstants.MS_IN_A_DAY)
            if (!date.isBefore(today)) return@filterTo false
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val timestamps = observationDao
                .getObservationsInRange(dayStart, dayEnd, latitude, longitude)
                .filter { it.api == WeatherSource.NWS.id && it.stationId != "NWS_BLEND" }
                .map { it.timestamp }
            pastDayLacksAfternoonCoverage(timestamps, date, zone, today)
        }

    private suspend fun logExtremaWindowDiagnostic(
        date: LocalDate,
        zone: ZoneId,
        startTs: Long,
        endTs: Long,
        dayObs: List<com.weatherwidget.data.local.ObservationEntity>,
        effectiveHourly: List<HourlyForecastEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        try {
            val hourlyReadings = effectiveHourly.map { it.toHourlyForecast() }
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            fun probe(observations: List<ObservationReading>, start: Long, end: Long): String {
                val series = ActualTemperatureSeriesBuilder
                    .blendObservationSeries(
                        observations = observations,
                        hourlyForecasts = hourlyReadings,
                        displaySourceId = WeatherSource.NWS.id,
                        userLat = latitude,
                        userLon = longitude,
                        startMs = start,
                        endMs = end,
                        personalStationWeight = personalStationWeightProvider.currentWeight(),
                    )
                    .observations
                    .filter { it.timestamp in startTs until endTs }
                if (series.isEmpty()) return "empty"
                val high = series.maxBy { it.temperature }
                val low = series.minBy { it.temperature }
                return "hi=${"%.2f".format(high.temperature)}@" +
                    "${Instant.ofEpochMilli(high.timestamp).atZone(zone).format(formatter)} " +
                    "lo=${"%.2f".format(low.temperature)}@" +
                    "${Instant.ofEpochMilli(low.timestamp).atZone(zone).format(formatter)} pts=${series.size}"
            }

            val nwsDayObs = dayObs.filter { it.api == WeatherSource.NWS.id }.map { it.toReading() }
            if (nwsDayObs.isNotEmpty()) {
                val dayMs = 24 * 3_600_000L
                val wideObs = observationDao
                    .getObservationsInRange(
                        startTs - dayMs,
                        endTs + dayMs,
                        latitude,
                        longitude,
                    )
                    .filter { it.api == WeatherSource.NWS.id }
                    .map { it.toReading() }
                appLogDao.log(
                    "EXTREMA_WINDOW_DIAG",
                    "date=$date isolated=[${probe(nwsDayObs, startTs, endTs)}] " +
                        "wide=[${probe(wideObs, startTs - dayMs, endTs + dayMs)}]",
                    "DEBUG",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.v(TAG, "Unable to write extrema window diagnostic", e)
        }
    }

}
