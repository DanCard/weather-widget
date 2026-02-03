package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
    tableName = "weather_data",
    primaryKeys = ["date", "source"]
)
data class WeatherEntity(
    val date: String,
    val locationLat: Double,
    val locationLon: Double,
    val locationName: String,
    val highTemp: Int?,
    val lowTemp: Int?,
    val currentTemp: Int?,
    val condition: String,
    val isActual: Boolean,
    val source: String = "Unknown",
    val stationId: String? = null,  // NWS observation station ID (e.g., "KSFO") - only for actual observations
    val fetchedAt: Long = System.currentTimeMillis()
)
