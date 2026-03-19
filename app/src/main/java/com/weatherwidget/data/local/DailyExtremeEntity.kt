package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Daily high/low temperature extremes per (date, source, location).
 *
 * Rows prefer official provider-supplied extremes when available and otherwise fall back
 * to the highest and lowest stored observations for that local day.
 */
@Entity(
    tableName = "daily_extremes",
    primaryKeys = ["date", "source", "locationLat", "locationLon"],
    indices = [
        Index(value = ["date", "locationLat", "locationLon"]),
    ],
)
data class DailyExtremeEntity(
    val date: String,           // "2026-03-18"
    val source: String,         // WeatherSource.id (NWS, OPEN_METEO, etc.)
    val locationLat: Double,
    val locationLon: Double,
    val highTemp: Float,        // Fahrenheit
    val lowTemp: Float,         // Fahrenheit
    val condition: String,
    val updatedAt: Long,        // epoch ms, used for cleanup
)
