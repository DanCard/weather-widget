package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "hourly_forecasts",
    primaryKeys = ["dateTime", "source", "locationLat", "locationLon"],
    indices = [Index(value = ["locationLat", "locationLon"])],
)
data class HourlyForecastEntity(
    val dateTime: String, // ISO 8601 format: "2024-01-15T14:00"
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float, // Temperature in Fahrenheit
    val condition: String, // Weather condition (e.g., "Cloudy", "Rain")
    val source: String, // Database storage: "NWS", "OPEN_METEO", or "Generic". Use WeatherSource.fromId() to convert.
    val precipProbability: Int? = null, // Precipitation probability percentage (0-100)
    val cloudCover: Int? = null, // Cloud cover percentage (0-100)
    val fetchedAt: Long, // When this data was fetched
)
