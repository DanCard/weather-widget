package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.HourLabelFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Diagnostic logger for coordinate-fragmentation and borrowing when unifying hourly forecasts to nearest site.
 * Extracted from [WidgetRenderer].
 */
object HourlyUnifyDiagnostic {

    suspend fun logIfDiscrepancy(
        context: Context,
        appWidgetId: Int,
        effectiveViewMode: ViewMode,
        origin: WidgetPushDispatcher.Origin,
        displaySource: WeatherSource,
        hourlyForecasts: List<HourlyForecastEntity>,
        unifiedHourlyForecasts: List<HourlyForecastEntity>,
        locationLat: Double,
        locationLon: Double,
    ) {
        val siteLat = unifiedHourlyForecasts.firstOrNull()?.locationLat ?: return
        val siteLon = unifiedHourlyForecasts.firstOrNull()?.locationLon ?: return

        val outHours = unifiedHourlyForecasts.asSequence()
            .filter { it.source == displaySource.id }
            .map { it.dateTime }
            .toSet()
        // Hours the winning site covered on its own, before any borrowing.
        val nativeHours = hourlyForecasts.asSequence()
            .filter {
                it.source == displaySource.id &&
                    LocationMatch.sameSite(siteLat, siteLon, it.locationLat, it.locationLon)
            }
            .map { it.dateTime }
            .toSet()
        val inHours = hourlyForecasts.asSequence()
            .filter { it.source == displaySource.id }
            .map { it.dateTime }
            .toSet()
        val borrowedMs = (outHours - nativeHours).toSortedSet()
        val lostMs = (inHours - outHours).toSortedSet()

        if (borrowedMs.isNotEmpty() || lostMs.isNotEmpty()) {
            val zone = ZoneId.systemDefault()
            fun ranges(ms: Set<Long>): String =
                HourLabelFormatter.missingHourRanges(
                    ms.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() },
                ).ifEmpty { "-" }
            val donorSites = hourlyForecasts.asSequence()
                .filter { it.dateTime in borrowedMs && it.source == displaySource.id }
                .filter { !LocationMatch.sameSite(siteLat, siteLon, it.locationLat, it.locationLon) }
                .map { String.format(Locale.US, "%.5f,%.5f", it.locationLat, it.locationLon) }
                .distinct()
                .toList()
                .joinToString("|")
                .ifEmpty { "-" }
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "HOURLY_UNIFY_DROP",
                "widget=$appWidgetId view=$effectiveViewMode origin=${origin.name} " +
                    "displaySource=${displaySource.id} in=${hourlyForecasts.size} " +
                    "out=${unifiedHourlyForecasts.size} " +
                    "borrowed=${borrowedMs.size} borrowedRanges=${ranges(borrowedMs)} " +
                    "lostHours=${lostMs.size} lostRanges=${ranges(lostMs)} " +
                    "site=$siteLat,$siteLon center=$locationLat,$locationLon donorSites=$donorSites",
                if (lostMs.isNotEmpty()) "WARN" else "INFO",
            )
        }
    }
}
