package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "weather_data")
data class WeatherEntity(
    @PrimaryKey
    val date: String,
    val locationLat: Double,
    val locationLon: Double,
    val locationName: String,
    val highTemp: Int,
    val lowTemp: Int,
    val currentTemp: Int?,
    val condition: String,
    val isActual: Boolean,
    val fetchedAt: Long = System.currentTimeMillis()
)
