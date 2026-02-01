package com.weatherwidget.data.local

import androidx.room.Entity

@Entity(
    tableName = "hourly_forecasts",
    primaryKeys = ["dateTime", "source", "locationLat", "locationLon"]
)
data class HourlyForecastEntity(
    val dateTime: String,          // ISO 8601 format: "2024-01-15T14:00"
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float,        // Temperature in Fahrenheit
    val source: String,            // "NWS" or "OPEN_METEO"
    val fetchedAt: Long            // When this data was fetched
)
