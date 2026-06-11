package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource

private const val TAG_FSP = "ForecastSourcePriority"

internal fun resolveForecastsByTime(
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
    now: Long = System.currentTimeMillis(),
): Map<Long, HourlyForecastEntity?> =
    hourlyForecasts.groupBy { it.dateTime }
        .mapValues { (dateTimeMs, rows) ->
            val candidates = when {
                rows.any { it.source == displaySource.id } -> rows.filter { it.source == displaySource.id }
                rows.any { it.source == WeatherSource.GENERIC_GAP.id } -> rows.filter { it.source == WeatherSource.GENERIC_GAP.id }
                else -> rows
            }
            // GPS drift causes the same source+timestamp to accumulate at slightly-different lat/lon
            // keys. For past hours prefer the earliest-fetched row (original prediction, preserving
            // forecast-accuracy intent); for future hours prefer the latest (freshest forecast).
            val picked = if (dateTimeMs < now) candidates.minByOrNull { it.fetchedAt }
                         else candidates.maxByOrNull { it.fetchedAt }
            if (picked?.temperature != null && picked.temperature <= 33f && dateTimeMs < now) {
                Log.w(TAG_FSP, "COLD: source=${displaySource.id} dt=$dateTimeMs temp=${picked.temperature} " +
                    "cond=${picked.condition} candidates=${candidates.size} " +
                    "rows=${candidates.map { "${it.temperature}@${it.locationLat} fetchedAt=${it.fetchedAt}" }}")
            }
            if (candidates.size > 1) {
                Log.d(TAG_FSP, "dedup: source=${displaySource.id} dt=$dateTimeMs isPast=${dateTimeMs < now} " +
                    "candidates=${candidates.size} picked=${picked?.temperature}/${picked?.condition} " +
                    "fetchedAts=${candidates.map { it.fetchedAt }}")
            }
            picked
        }
