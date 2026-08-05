package com.weatherwidget.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily high/low temperature extremes and precipitation actuals per (date, source, location).
 */
data class DailyHistory(
    val date: Long,             // UTC midnight epoch millis
    val source: String,         // WeatherSource.id (NWS, OPEN_METEO, etc.)
    val locationLat: Double,
    val locationLon: Double,
    val computedHighTemp: Float, // °F — blended extreme from IDW observation pipeline ("Location actual")
    val computedLowTemp: Float,  // °F — blended extreme
    val condition: String,
    val updatedAt: Long,        // epoch ms, used for cleanup
    val precipAmountMm: Float? = null, // Daily observed precipitation amount in mm (total)
    val precipDayMm: Float? = null, // Daytime (8AM-8PM) observed precipitation in mm
    val precipNightMm: Float? = null, // Nighttime (8PM-8AM) observed precipitation in mm
    // Resolved (as-displayed) forecast rain chance %, snapshotted while the day was current so
    // history replays what the widget showed instead of NWS's raw 6am/6pm period fields (see
    // DailyRainLabels.resolveDailyLabelPrecip). Null for rows written before this feature.
    val forecastDayPrecipChance: Int? = null,
    val forecastNightPrecipChance: Int? = null,
    // Frozen forecast-overlay values (yellow accuracy bar) and noon cloud %, snapshotted while the
    // day was current so the daily bar view can render past days from this row alone — without the
    // forecasts / hourly tables whose retention may be shorter (see DailyHistoryFreeze). High/low
    // move as a unit (both null or both set). Null for rows written before this feature.
    val forecastHighTemp: Float? = null,
    val forecastLowTemp: Float? = null,
    val forecastPrecipAmountMm: Float? = null,
    val noonCloudPercent: Int? = null,
    val apiHighTemp: Float? = null,  // °F — API-reported observed high; null when source provides no native actuals
    val apiLowTemp: Float? = null,   // °F — API-reported observed low; null when source provides no native actuals
) {
    fun toLocalDate(): LocalDate =
        LocalDate.ofEpochDay(date / 86_400_000L)

    fun toDailyActual() = DailyActual(
        date = toLocalDate().toString(),
        computedHighTemp = computedHighTemp,
        computedLowTemp = computedLowTemp,
        condition = condition,
        apiHighTemp = apiHighTemp,
        apiLowTemp = apiLowTemp,
    )
}
