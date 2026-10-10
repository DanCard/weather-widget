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
    // Same-day high/low the source sent after its SameDayExtremeCutoff: kept, never read as a
    // forecast (highTemp/lowTemp hold the last pre-cutoff value). Past-day dashed fallback only.
    val hindcastHighTemp: Float? = null,
    val hindcastLowTemp: Float? = null,
    // What the daily view reads from hourly rows, kept here so hourly is stored to 72 h only
    // (DailyHourlySummaries): noon cloud % and the 8am–8pm / 8pm–8am rain maxima. A fetch that does
    // not cover a window carries the previous row's value forward; never blanked.
    val noonCloudPercent: Int? = null,
    val hourlyDayPrecipMax: Int? = null,
    val hourlyNightPrecipMax: Int? = null,
)

val ForecastEntity.hourlySummary: com.weatherwidget.shared.util.DailyHourlySummaries.Summary
    get() = com.weatherwidget.shared.util.DailyHourlySummaries.Summary(noonCloudPercent, hourlyDayPrecipMax, hourlyNightPrecipMax)

fun ForecastEntity.withHourlySummary(summary: com.weatherwidget.shared.util.DailyHourlySummaries.Summary): ForecastEntity = copy(
    noonCloudPercent = summary.noonCloudPercent,
    hourlyDayPrecipMax = summary.dayPrecipMax,
    hourlyNightPrecipMax = summary.nightPrecipMax,
)
