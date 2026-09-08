package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.MetarCloudBlender
import com.weatherwidget.shared.graph.CloudBands
import com.weatherwidget.shared.observations.ActualsProviderResolver

internal object CloudSeriesLoader {
    private const val TAG = "CloudSeriesLoader"

    data class CloudSeriesData(
        val siteLat: Double?,
        val siteLon: Double?,
        val windowStart: Long,
        val windowEnd: Long,
        val priorCloud: Map<Long, Int>,
        val priorBands: Map<Long, CloudBands>,
        val retroActual: MetarCloudBlender.Result,
    )

    suspend fun loadCloudSeries(
        context: Context,
        hourlyForecasts: List<HourlyForecastEntity>,
        windowHourKeys: Set<Long>,
        effectiveDisplaySource: WeatherSource,
    ): CloudSeriesData {
        val siteLat = hourlyForecasts.firstOrNull()?.locationLat
        val siteLon = hourlyForecasts.firstOrNull()?.locationLon
        val siteResolved = siteLat != null && siteLon != null && windowHourKeys.isNotEmpty()

        val cloudProvider =
            WeatherSource.fromId(ActualsProviderResolver.providerIdFor(effectiveDisplaySource))
        val cloudSeriesAvailable = siteResolved && cloudProvider.supportsCloudActuals
        val priorCloudAvailable = siteResolved && effectiveDisplaySource == WeatherSource.OPEN_METEO
        val cloudHistoryDao = if (priorCloudAvailable) {
            WeatherDatabase.getDatabase(context).hourlyForecastHistoryDao()
        } else {
            null
        }
        val windowStart = windowHourKeys.minOrNull() ?: 0L
        val windowEnd = (windowHourKeys.maxOrNull() ?: 0L) + 1

        val priorCloud = if (cloudHistoryDao != null) {
            runCatching {
                cloudHistoryDao.getPriorDayCloudForecast(
                    startDateTime = windowStart,
                    endDateTime = windowEnd,
                    lat = siteLat!!,
                    lon = siteLon!!,
                )
            }.getOrElse {
                Log.w(TAG, "prior-day cloud read failed; falling back to live values", it)
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val priorBands = if (cloudHistoryDao != null) {
            runCatching {
                cloudHistoryDao.getPriorDayBandForecast(
                    startDateTime = windowStart,
                    endDateTime = windowEnd,
                    lat = siteLat!!,
                    lon = siteLon!!,
                    source = effectiveDisplaySource.id,
                )
            }.getOrElse {
                Log.w(TAG, "prior-day band read failed; band glyphs stay forecast-only", it)
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val retroActual = if (cloudSeriesAvailable) {
            runCatching {
                WeatherDatabase.getDatabase(context).observationDao().getCloudActuals(
                    startTs = windowStart,
                    endTs = minOf(windowEnd, System.currentTimeMillis()),
                    lat = siteLat,
                    lon = siteLon,
                    sourceId = effectiveDisplaySource.id,
                )
            }.getOrElse {
                Log.w(TAG, "cloud actual read failed; graph shows forecast only", it)
                MetarCloudBlender.empty(isMetarBlend = false)
            }
        } else {
            MetarCloudBlender.empty(isMetarBlend = false)
        }

        val metarStats = if (retroActual.isMetarBlend) " ${retroActual.stats.summary()}" else ""
        Log.i(
            TAG,
            "CLOUD_SERIES src=${effectiveDisplaySource.id} site=$siteLat,$siteLon " +
                "window=${windowStart}..${windowEnd} prior=${priorCloud.size} actual=${retroActual.hours.size} " +
                "inWindow=${retroActual.hours.keys.count { it in windowStart until windowEnd }}$metarStats",
        )

        return CloudSeriesData(
            siteLat = siteLat,
            siteLon = siteLon,
            windowStart = windowStart,
            windowEnd = windowEnd,
            priorCloud = priorCloud,
            priorBands = priorBands,
            retroActual = retroActual,
        )
    }
}
