package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Stores individual observations from specific weather stations.
 * Used to capture "micro-climate" discrepancies between nearby stations (e.g., NWS airports vs. PWS).
 * Serves as source of truth for actual weather data in accuracy calculations.
 */
@Entity(
    tableName = "observations",
    primaryKeys = ["stationId", "timestamp"],
    indices = [
        Index(value = ["locationLat", "locationLon"]),
        Index(value = ["timestamp", "locationLat", "locationLon"])
    ],
)
data class ObservationEntity(
    val stationId: String,
    val stationName: String,
    val timestamp: Long, // Epoch ms
    val temperature: Float, // Fahrenheit
    val condition: String,
    val locationLat: Double,
    val locationLon: Double,
    val distanceKm: Float = 0f,
    val stationType: String = "UNKNOWN",
    val fetchedAt: Long = System.currentTimeMillis()
)
