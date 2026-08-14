package com.weatherwidget.data.model

/**
 * The resolved "what should we show right now" status, published by the daemon after each fetch.
 *
 * This is the single-owner snapshot the genmon panel and the popup header will consume (instead of
 * each process re-deriving it from its own in-memory [ForecastSnapshot] — the split-brain that made
 * the panel and popup drift). All temperatures are °F; unit conversion happens at display time.
 *
 * Keyed by (locationLat, locationLon, source), mirroring the other coordinate-keyed weather tables.
 */
data class CurrentStatus(
    val locationLat: Double,
    val locationLon: Double,
    val source: String,               // WeatherSource.id (NWS, OPEN_METEO, …)
    val displayTempF: Float?,         // resolved current temperature, null when not resolvable
    val appliedDeltaF: Float?,        // observed minus forecast at the same instant (°F)
    val deltaFromYesterdayF: Float?,  // observed minus blended actual 24h earlier (°F)
    val observedAtMs: Long?,          // timestamp of the observation driving the display
    val condition: String?,           // current condition, for the header icon
    val updatedAt: Long,              // epoch ms, for staleness checks
)
