package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.HourlyForecastSelector

/**
 * Android wrapper over the shared [HourlyForecastSelector]. Maps the location-keyed entities to the
 * shared model (which now carries the storage-key coordinates), runs the one shared selection rule
 * (source priority -> same-site collapse -> freshest), then maps each winning forecast back to its
 * source entity so existing callers keep their entity-typed map.
 *
 * The selection centre defaults to the freshest row's coordinate — the most recent fetch reflects
 * the user's current location, so same-site collapse anchors on the live site rather than a stale
 * fragment left at an old fetch coordinate. The shared/desktop path passes the real configured
 * centre into [com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder]; here that centre is
 * not threaded in, and the freshest-row anchor is equivalent in practice.
 */
internal fun resolveForecastsByTime(
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
): Map<Long, HourlyForecastEntity?> {
    val anchor = hourlyForecasts.maxByOrNull { it.fetchedAt } ?: return emptyMap()
    val byKey = hourlyForecasts.associateBy { rowKey(it.dateTime, it.source, it.fetchedAt, it.locationLat, it.locationLon) }
    return HourlyForecastSelector
        .selectForecastsByTime(
            rows = hourlyForecasts.map { it.toHourlyForecast() },
            displaySourceId = displaySource.id,
            centerLat = anchor.locationLat,
            centerLon = anchor.locationLon,
        )
        .mapValues { (_, hf) -> byKey[rowKey(hf.dateTime, hf.source ?: "", hf.fetchedAt, hf.locationLat, hf.locationLon)] }
}

private fun rowKey(dateTime: Long, source: String, fetchedAt: Long, lat: Double?, lon: Double?): String =
    "$dateTime|$source|$fetchedAt|$lat|$lon"
