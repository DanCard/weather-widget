package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "forecasts",
    primaryKeys = ["targetDate", "forecastDate", "locationLat", "locationLon", "source", "fetchedAt"],
    indices = [
        Index(value = ["locationLat", "locationLon"]),
        Index(value = ["targetDate"]),
        Index(value = ["targetDate", "source", "locationLat", "locationLon", "forecastDate", "fetchedAt"])
    ],
)
data class ForecastEntity(
    val targetDate: String, // Date being forecasted (e.g., "2024-01-15")
    val forecastDate: String, // When forecast was made (e.g., "2024-01-14" for 1-day-ahead)
    val locationLat: Double,
    val locationLon: Double,
    val locationName: String = "", // Human-readable location name
    val highTemp: Float?,
    val lowTemp: Float?,
    val condition: String,
    val isClimateNormal: Boolean = false, // Historical averages
    val source: String, // Database storage: "NWS", "OPEN_METEO", "WEATHER_API", or "GENERIC_GAP"
    val precipProbability: Int? = null, // Rain chance percentage (0-100)
    val periodStartTime: String? = null,  // NWS only: ISO-8601 start of the daytime forecast period
    val periodEndTime: String? = null,    // NWS only: ISO-8601 end of the daytime forecast period
    val fetchedAt: Long = System.currentTimeMillis(),
)
