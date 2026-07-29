package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetQueryWindows
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object GraphDataLoader {
    internal fun buildGraphQueryWindow(
        centerTime: LocalDateTime,
        zoom: ZoomLevel,
        now: LocalDateTime,
    ): GraphQueryWindow {
        val truncatedCenter = centerTime.truncatedTo(ChronoUnit.HOURS)
        val roundedCenter = if (centerTime.minute >= 30) truncatedCenter.plusHours(1) else truncatedCenter
        // Size the query to what CONSUMES the forecasts, not to what is VISIBLE. The visible span is
        // zoom.backHours/forwardHours (±2h at NARROW), but ActualTemperatureSeriesBuilder blends over
        // contextLookback/LookaheadHours (72h/60h) and extrapolates each station forward via the
        // forecast delta — `forecastTemperatureAt(series, before.ts) ?: return null`. Query only the
        // visible ±2h and that lookup returns null across nearly the whole context, so every
        // extrapolating station resolves to null and drops out of the blend: the observed curve
        // flattens to a plateau, loses its interior extrema, and the high/low labels vanish.
        //
        // That made the graph alternate. The interaction path (WidgetIntentRouter -> here) supplied 7
        // forecasts and rendered flat/label-less; the background repaint (WeatherWidgetProvider ->
        // WidgetRenderer), which queries now ± 72/168h, supplied 226 and rendered inclined/labelled.
        // Same widget, same centre, same observations — two loaders, two curves, flipping ~1 min apart.
        //
        // maxOf keeps this correct if a zoom ever reaches past the blend context.
        val lookbackHours = maxOf(zoom.backHours, WidgetQueryWindows.HOURLY_LOOKBACK_HOURS)
        val lookaheadHours = maxOf(zoom.forwardHours, WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS)
        val centerStart = roundedCenter.minusHours(lookbackHours)
        val centerEnd = roundedCenter.plusHours(lookaheadHours)

        val nowStart = now.truncatedTo(ChronoUnit.HOURS)
        val nowEnd = nowStart.plusHours(1)
        val overlaps = !nowEnd.isBefore(centerStart) && !nowStart.isAfter(centerEnd)

        return if (overlaps) {
            GraphQueryWindow(centerStart = centerStart, centerEnd = centerEnd, nowStart = null, nowEnd = null)
        } else {
            GraphQueryWindow(centerStart = centerStart, centerEnd = centerEnd, nowStart = nowStart, nowEnd = nowEnd)
        }
    }

    internal data class GraphQueryWindow(
        val centerStart: LocalDateTime,
        val centerEnd: LocalDateTime,
        val nowStart: LocalDateTime?,
        val nowEnd: LocalDateTime?,
    )

    /**
     * Collapse a raw proximity-box query result to the single physical site nearest (lat, lon).
     * Sub-precision fragments of that site are kept (LocationMatch.sameSite box); genuinely
     * different markers left behind by earlier GPS fixes (e.g. 37.39 vs 37.417) are dropped —
     * those sites stop being refreshed, and their frozen forecasts otherwise win
     * firstOrNull-style selections downstream (DailyNoonCloudCover picked a 2-day-old noon
     * cloud row over the fresh one, flapping the daily bar's cloud split between renders).
     */
    fun unifyToNearestSite(
        rows: List<HourlyForecastEntity>,
        lat: Double,
        lon: Double,
    ): List<HourlyForecastEntity> =
        LocationMatch.selectNearestSite(rows, lat, lon, { it.locationLat }, { it.locationLon })

    suspend fun loadGraphWindowHourlyForecasts(
        hourlyDao: HourlyForecastDao,
        hourlyHistoryDao: HourlyForecastHistoryDao? = null,
        lat: Double,
        lon: Double,
        centerTime: LocalDateTime,
        zoom: ZoomLevel,
        now: LocalDateTime,
        source: WeatherSource? = null,
    ): List<HourlyForecastEntity> {
        val window = buildGraphQueryWindow(centerTime, zoom, now)
        val zoneId = ZoneId.systemDefault()
        val centerStartMs = window.centerStart.atZone(zoneId).toInstant().toEpochMilli()
        val centerEndMs = window.centerEnd.atZone(zoneId).toInstant().toEpochMilli()

        // Match within the same-site box (0.002°), NOT exact float equality: stored coordinates are
        // quantized on write (LocationMatch.quantize, 3 dp) while the query centre is the raw
        // configured/GPS coordinate, so the two differ by up to ~0.0005°. An exact filter here would
        // drop every cached row after quantization and blank the graph until a network fetch lands.
        val centerRows = if (source != null) {
            hourlyDao.getHourlyForecastsBySource(centerStartMs, centerEndMs, lat, lon, source.id)
        } else {
            hourlyDao.getHourlyForecasts(centerStartMs, centerEndMs, lat, lon)
        }.filter {
            LocationMatch.sameSite(lat, lon, it.locationLat, it.locationLon)
        }
        val currentRows = if (window.nowStart == null || window.nowEnd == null) {
            centerRows
        } else {
            val nowStartMs = window.nowStart.atZone(zoneId).toInstant().toEpochMilli()
            val nowEndMs = window.nowEnd.atZone(zoneId).toInstant().toEpochMilli()

            val nowRows = if (source != null) {
                hourlyDao.getHourlyForecastsBySource(nowStartMs, nowEndMs, lat, lon, source.id)
            } else {
                hourlyDao.getHourlyForecasts(nowStartMs, nowEndMs, lat, lon)
            }.filter {
                LocationMatch.sameSite(lat, lon, it.locationLat, it.locationLon)
            }

            (centerRows + nowRows)
                .distinctBy { "${it.dateTime}|${it.source}|${it.locationLat}|${it.locationLon}" }
                .sortedBy { it.dateTime }
        }

        if (hourlyHistoryDao == null || source == null) {
            return currentRows
        }

        val historyRows = hourlyHistoryDao.getHistoryInRangeForBucketWindow(
            startDateTime = centerStartMs,
            endDateTime = centerEndMs,
            bucketStart = Long.MIN_VALUE,
            bucketEnd = Long.MAX_VALUE,
            lat = lat,
            lon = lon,
            source = source.id,
        ).map {
            HourlyForecastEntity(
                dateTime = it.dateTime,
                locationLat = it.locationLat,
                locationLon = it.locationLon,
                temperature = it.temperature,
                condition = it.condition,
                source = it.source,
                precipProbability = it.precipProbability,
                cloudCover = it.cloudCover,
                precipAmountMm = it.precipAmountMm,
                fetchedAt = it.fetchedAt,
            )
        }

        // Same shared merge desktop uses: the latest forecast wins for every hour — live (freshest)
        // wins whenever present, and the freshest history snapshot backfills hours live lacks plus any
        // missing nullable fields; same-site fragments are collapsed. All raw buckets/fragments are
        // passed in — the stitcher does the selection.
        val nowMs = now.atZone(zoneId).toInstant().toEpochMilli()
        val stitched = HourlyForecastStitcher.stitch(
            current = currentRows.map { it.toHourlyForecast() },
            history = historyRows.map { it.toHourlyForecast() },
            nowMs = nowMs,
            centerLat = lat,
            centerLon = lon,
        )
        return stitched.map { it.toEntity(lat, lon) }
    }

    /** Map a stitched [com.weatherwidget.data.model.HourlyForecast] back to an entity for the
     *  downstream graph pipeline; coordinates are always present (they came from a DB row) but fall
     *  back to the query centre defensively. */
    private fun com.weatherwidget.data.model.HourlyForecast.toEntity(
        fallbackLat: Double,
        fallbackLon: Double,
    ): HourlyForecastEntity =
        HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = locationLat ?: fallbackLat,
            locationLon = locationLon ?: fallbackLon,
            temperature = temperature,
            condition = condition,
            source = source ?: WeatherSource.GENERIC_GAP.id,
            precipProbability = precipProbability,
            cloudCover = cloudCover,
            precipAmountMm = precipAmountMm,
            fetchedAt = fetchedAt,
        )

    suspend fun loadCurrentTempResolutionHourlyForecasts(
        hourlyDao: HourlyForecastDao,
        lat: Double,
        lon: Double,
        now: LocalDateTime,
    ): List<HourlyForecastEntity> {
        val window = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val zoneId = ZoneId.systemDefault()
        return hourlyDao.getHourlyForecasts(
            window.start.atZone(zoneId).toInstant().toEpochMilli(),
            window.end.atZone(zoneId).toInstant().toEpochMilli(),
            lat,
            lon,
        ).filter {
            LocationMatch.sameSite(lat, lon, it.locationLat, it.locationLon)
        }
    }
}
