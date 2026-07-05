package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "forecasts",
    primaryKeys = ["targetDate", "dateOfPrediction", "locationLat", "locationLon", "source", "fetchedAt"],
    indices = [
        Index(value = ["locationLat", "locationLon"]),
        Index(value = ["targetDate", "source", "locationLat", "locationLon", "batchFetchedAt"]),
    ],
)
data class ForecastEntity(
    val targetDate: Long, // Date being forecasted (UTC midnight epoch millis)
    val dateOfPrediction: Long, // When forecast prediction was generated (UTC midnight epoch millis, e.g. 1-day-ahead)
    val locationLat: Double,
    val locationLon: Double,
    val locationName: String = "", // Human-readable location name
    val highTemp: Float?,
    val lowTemp: Float?,
    val condition: String,
    val nativeDailyIconToken: String? = null, // Provider-native daily icon token/code when available
    val isClimateNormal: Boolean = false, // Historical averages
    val source: String, // Database storage: "NWS", "OPEN_METEO", "WEATHER_API", or "GENERIC_GAP"
    val precipProbability: Int? = null, // Rain chance percentage (0-100)
    val daytimePrecipProbability: Int? = null, // NWS daytime period rain chance percentage (0-100)
    val nighttimePrecipProbability: Int? = null, // NWS nighttime period rain chance percentage (0-100)
    val periodStartTime: Long? = null,  // NWS only: epoch millis of daytime forecast period start
    val periodEndTime: Long? = null,    // NWS only: epoch millis of daytime forecast period end
    val precipAmountMm: Float? = null, // Daily precipitation amount in millimeters
    val batchFetchedAt: Long = System.currentTimeMillis(), // Shared across all rows from one provider fetch batch
    val fetchedAt: Long = System.currentTimeMillis(),
)
