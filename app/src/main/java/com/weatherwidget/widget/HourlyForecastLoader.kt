package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime
import java.time.ZoneId

internal class HourlyForecastLoader(
    private val context: Context,
    private val widgetStateManager: WidgetStateManager,
) {
    /**
     * Sources the hourly render data must cover: every installed widget's display source, plus
     * `GENERIC_GAP` (the climate-normal filler consumers accept alongside the display source).
     */
    fun hourlySourceIds(): List<String> =
        (currentDisplaySourceIds() + WeatherSource.GENERIC_GAP.id).distinct()

    fun currentDisplaySourceIds(): List<String> {
        val componentName = ComponentName(context, WeatherWidgetProvider::class.java)
        return AppWidgetManager.getInstance(context)
            .getAppWidgetIds(componentName)
            .map { widgetStateManager.getCurrentDisplaySource(it).id }
            .distinct()
    }

    /**
     * @param sources display source of each installed widget plus `GENERIC_GAP`. Filtering in SQL
     *   rather than in memory: unfiltered, these two queries returned ~38k rows on a Samsung Fold
     *   (4928 hourly + 33473 history) where the display source accounted for ~8.4k, and every
     *   consumer downstream filters to the display source anyway. This was the `hourly=1888ms`
     *   stage in SYNC_PERF.
     */
    suspend fun load(lat: Double, lon: Double, sources: List<String>): List<HourlyForecastEntity> {
        return try {
            val database = WeatherDatabase.getDatabase(context)
            val hourlyDao = database.hourlyForecastDao()
            val historyDao = database.hourlyForecastHistoryDao()
            val now = LocalDateTime.now()
            val zoneId = ZoneId.systemDefault()
            val startTimeMs = now.minusHours(72).atZone(zoneId).toInstant().toEpochMilli()
            val endTimeMs = now.plusHours(168).atZone(zoneId).toInstant().toEpochMilli()
            Log.d(TAG, "load: range=${now.minusHours(72)} to ${now.plusHours(168)} (ms=$startTimeMs to $endTimeMs)")
            val current = hourlyDao.getHourlyForecastsForSources(startTimeMs, endTimeMs, lat, lon, sources)
            val history = historyDao.getHistoryInRangeForBucketWindowForSources(
                startDateTime = startTimeMs,
                endDateTime = endTimeMs,
                bucketStart = Long.MIN_VALUE,
                bucketEnd = Long.MAX_VALUE,
                lat = lat,
                lon = lon,
                sources = sources,
            )

            // The SHARED stitcher, identical to GraphDataLoader's merge — not a local reimplementation.
            // This path used to pick a nearest site, re-filter with sameSite against that site's
            // QUANTIZED coordinates, then collapse with `associateBy { Pair(dateTime, source) }`. All
            // three steps were wrong together:
            //   * re-centering on the quantized site (37.417) instead of the raw query centre
            //     (37.41681671...) moved a frozen fragment 0.002 deg away from "excluded" (0.0021832886)
            //     to "admitted" (0.001999999999995339) -- a floating-point hair.
            //   * `associateBy` is last-wins and IGNORES fetchedAt.
            //   * the DAO orders `dateTime ASC`, and SQLite breaks ties on
            //     index_hourly_forecasts_locationLat_locationLon -- ascending latitude.
            // So the higher-latitude fragment deterministically overwrote the fresh row, and a
            // 2026-07-24 forecast (81.3 deg) beat that day's 19:23 fetch (66.6 deg), driving the
            // today-column delta to -13.7. GraphDataLoader, filtering against the RAW centre, kept the
            // fresh row -- so the widget alternated between -13.7 and +0.5 depending on which loader
            // rendered last. The stitcher picks `maxByOrNull { fetchedAt }` per hour and same-sites
            // against the raw centre, so neither row order nor centre form can decide the outcome.
            // See plans/260806-today-column-stale-fragment-delta-opus.md.
            val stitched = HourlyForecastStitcher.stitchBySource(
                current = current.map { it.toHourlyForecast() },
                history = history.map { it.toHourlyForecast() },
                nowMs = now.atZone(zoneId).toInstant().toEpochMilli(),
                centerLat = lat,
                centerLon = lon,
            ).map { it.toEntity(lat, lon) }
            Log.i(
                TAG,
                "load: stitched=${stitched.size} from current=${current.size} history=${history.size} " +
                    "center=$lat,$lon sites=${current.map { it.locationLat to it.locationLon }.distinct().size}",
            )
            stitched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch hourly forecasts", e)
            emptyList()
        }
    }

    private companion object {
        private const val TAG = "HourlyForecastLoader"
    }
}
