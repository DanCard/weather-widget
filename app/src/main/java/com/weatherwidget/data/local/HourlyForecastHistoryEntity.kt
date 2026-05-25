package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Historical snapshots of the hourly forecast — what each hour was *predicted* to be, captured at
 * a [snapshotBucket] (4h cadence for the primary source, 8h for others; see
 * [com.weatherwidget.data.repository.ForecastHistoryPolicy]).
 *
 * Unlike [HourlyForecastEntity] (latest-only, REPLACE-overwritten), this preserves prior
 * predictions: [snapshotBucket] is part of the primary key, so each bucket keeps its own row.
 * Fetches within the same bucket collapse to one snapshot (the latest, via REPLACE). Payload mirrors
 * [HourlyForecastEntity]; [fetchedAt] is the real fetch time (kept for debugging/retention).
 */
@Entity(
    tableName = "hourly_forecast_history",
    primaryKeys = ["dateTime", "source", "locationLat", "locationLon", "snapshotBucket"],
    indices = [Index(value = ["locationLat", "locationLon", "source", "snapshotBucket"])],
)
data class HourlyForecastHistoryEntity(
    val dateTime: Long, // Epoch ms of the forecasted hour
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float, // Fahrenheit
    val condition: String,
    val source: String,
    val snapshotBucket: Long, // Bucket-aligned epoch ms identifying when this prediction was captured
    val precipProbability: Int? = null, // 0-100
    val cloudCover: Int? = null, // 0-100
    val precipAmountMm: Float? = null,
    val fetchedAt: Long, // Real fetch time
)
