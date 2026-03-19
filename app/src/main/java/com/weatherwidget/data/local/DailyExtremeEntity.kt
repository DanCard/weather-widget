package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Pre-computed daily high/low temperature extremes per (date, source, location).
 *
 * Populated after each observation ingestion batch. Using REPLACE on insert means
 * successive ingestion runs recompute and overwrite that day's extremes automatically.
 * Retained for 30 days matching forecast snapshot retention.
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
