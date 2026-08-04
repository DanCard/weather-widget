package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
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
            val bestLat: Double
            val bestLon: Double
            val bestPair = current.asSequence()
                .map { it.locationLat to it.locationLon }
                .distinct()
                .minByOrNull { (rowLat, rowLon) ->
                    Math.abs(rowLat - lat) + Math.abs(rowLon - lon)
                }
            if (bestPair != null) {
                bestLat = bestPair.first
                bestLon = bestPair.second
            } else {
                bestLat = lat
                bestLon = lon
            }
            val filteredCurrent = if (bestPair != null) {
                current.filter { LocationMatch.sameSite(it.locationLat, it.locationLon, bestLat, bestLon) }
            } else current
            val history = historyDao.getHistoryInRangeForBucketWindowForSources(
                startDateTime = startTimeMs,
                endDateTime = endTimeMs,
                bucketStart = Long.MIN_VALUE,
                bucketEnd = Long.MAX_VALUE,
                lat = bestLat,
                lon = bestLon,
                sources = sources,
            ).filter { LocationMatch.sameSite(it.locationLat, it.locationLon, bestLat, bestLon) }
                .map {
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
            val stitched = (history + filteredCurrent)
                .associateBy { Pair(it.dateTime, it.source) }
                .values
                .sortedBy { it.dateTime }
            if (stitched.size != filteredCurrent.size) {
                Log.i(TAG, "load: stitched ${stitched.size - filteredCurrent.size} missing rows from history (bestLoc=$bestLat,$bestLon)")
            }
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
