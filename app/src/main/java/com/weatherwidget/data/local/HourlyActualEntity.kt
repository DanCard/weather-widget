package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "hourly_actuals",
    primaryKeys = ["dateTime", "source", "locationLat", "locationLon"],
    indices = [Index(value = ["locationLat", "locationLon"])],
)
data class HourlyActualEntity(
    val dateTime: String, // ISO 8601 format: "2024-01-15T14:00"
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float, // Temperature in Fahrenheit
    val condition: String, // Weather condition (e.g., "Cloudy", "Rain")
    val source: String, // "NWS", "OPEN_METEO", "WEATHER_API", "SILURIAN"
    val fetchedAt: Long, // When this data was fetched
)
