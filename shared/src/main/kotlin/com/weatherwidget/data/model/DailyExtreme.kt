package com.weatherwidget.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily high/low temperature extremes and precipitation actuals per (date, source, location).
 */
data class DailyExtreme(
    val date: Long,             // UTC midnight epoch millis
    val source: String,         // WeatherSource.id (NWS, OPEN_METEO, etc.)
    val locationLat: Double,
    val locationLon: Double,
    val highTemp: Float,        // Fahrenheit
    val lowTemp: Float,         // Fahrenheit
    val condition: String,
    val updatedAt: Long,        // epoch ms, used for cleanup
    val precipAmountMm: Float? = null, // Daily observed precipitation amount in mm (total)
    val precipDayMm: Float? = null, // Daytime (8AM-8PM) observed precipitation in mm
    val precipNightMm: Float? = null, // Nighttime (8PM-8AM) observed precipitation in mm
) {
    fun toLocalDate(): LocalDate =
        LocalDate.ofEpochDay(date / 86_400_000L)

    fun toDailyActual() = DailyActual(
        date = toLocalDate().toString(),
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = condition
    )
}
