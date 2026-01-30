package com.weatherwidget.data.local

import androidx.room.Entity

@Entity(
    tableName = "forecast_snapshots",
    primaryKeys = ["targetDate", "forecastDate", "locationLat", "locationLon", "source"]
)
data class ForecastSnapshotEntity(
    val targetDate: String,      // Date being forecasted (e.g., "2024-01-15")
    val forecastDate: String,    // When forecast was made (e.g., "2024-01-14" for 1-day-ahead)
    val locationLat: Double,
    val locationLon: Double,
    val highTemp: Int,
    val lowTemp: Int,
    val condition: String,
    val source: String,          // "NWS" or "OPEN_METEO"
    val fetchedAt: Long = System.currentTimeMillis()
)
