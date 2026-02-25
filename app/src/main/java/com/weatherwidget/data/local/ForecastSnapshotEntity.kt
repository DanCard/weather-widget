package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "forecast_snapshots",
    primaryKeys = ["targetDate", "forecastDate", "locationLat", "locationLon", "source", "fetchedAt"],
    indices = [Index(value = ["locationLat", "locationLon"])],
)
data class ForecastSnapshotEntity(
    val targetDate: String, // Date being forecasted (e.g., "2024-01-15")
    val forecastDate: String, // When forecast was made (e.g., "2024-01-14" for 1-day-ahead)
    val locationLat: Double,
    val locationLon: Double,
    val highTemp: Float?,
    val lowTemp: Float?,
    val condition: String,
    val source: String, // Database storage: "NWS", "OPEN_METEO", or "Generic". Use WeatherSource.fromId() to convert.
    val fetchedAt: Long = System.currentTimeMillis(),
)
