package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "forecasts",
    primaryKeys = ["targetDate", "dateOfPrediction", "locationLat", "locationLon", "source", "fetchedAt"],
    indices = [
        Index(value = ["locationLat", "locationLon"]),
        Index(value = ["targetDate", "source", "locationLat", "locationLon", "lastConfirmedAt"]),
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
    val lastConfirmedAt: Long = System.currentTimeMillis(), // When this forecast was last confirmed by a fetch
    val fetchedAt: Long = System.currentTimeMillis(),
    // Same-day high the source sent after its SameDayExtremeCutoff: kept, never read as a forecast
    // (highTemp holds the last pre-cutoff value). Past-day dashed fallback only. hindcastLowTemp is
    // no longer written (today's low is never frozen since 2026-11-06); older rows may hold one.
    val hindcastHighTemp: Float? = null,
    val hindcastLowTemp: Float? = null,
    // What the daily view reads from hourly rows, kept here so hourly is stored to 72 h only
    // (DailyHourlySummaries): noon cloud % and the 8am–8pm / 8pm–8am rain maxima. A fetch that does
    // not cover a window carries the previous row's value forward; never blanked.
    val noonCloudPercent: Int? = null,
    val hourlyDayPrecipMax: Int? = null,
    val hourlyNightPrecipMax: Int? = null,
    // Epoch ms of the first hour holding hourlyDayPrecipMax (the daily view's peak-hour label).
    val hourlyDayPrecipPeakAt: Long? = null,
    // The morning's fog from the same hours (ForecastMorningFog): level code, burn-off °F, lowest
    // visibility (statute miles). Level 0 = covered, no fog. plans/261111-daily-bar-morning-fog.md
    val fogLevel: Int? = null,
    val fogTopTemp: Float? = null,
    val fogVisibilityMi: Float? = null,
    // True when every foggy hour came before sunrise (drawn petite). Null = no fog, or written before v88.
    val fogPredawn: Boolean? = null,
)

val ForecastEntity.hourlySummary: com.weatherwidget.shared.util.DailyHourlySummaries.Summary
    get() = com.weatherwidget.shared.util.DailyHourlySummaries.Summary(
        noonCloudPercent, hourlyDayPrecipMax, hourlyNightPrecipMax, hourlyDayPrecipPeakAt,
        fog = com.weatherwidget.shared.fog.MorningFog.fromColumns(fogLevel, fogTopTemp, fogVisibilityMi, fogPredawn),
    )

fun ForecastEntity.withHourlySummary(summary: com.weatherwidget.shared.util.DailyHourlySummaries.Summary): ForecastEntity = copy(
    noonCloudPercent = summary.noonCloudPercent,
    hourlyDayPrecipMax = summary.dayPrecipMax,
    hourlyNightPrecipMax = summary.nightPrecipMax,
    hourlyDayPrecipPeakAt = summary.dayPrecipPeakAt,
    fogLevel = summary.fog?.level,
    fogTopTemp = summary.fog?.topTempF,
    fogVisibilityMi = summary.fog?.visibilityMi,
    fogPredawn = com.weatherwidget.shared.fog.MorningFog.predawnColumn(summary.fog),
)

/** This row as the shared daily model's shown row for [date] (`:shared` DailyColumnBuilder). */
fun ForecastEntity.toDailyForecast(date: java.time.LocalDate) = com.weatherwidget.data.model.DailyForecast(
    date = date.toString(),
    highTemp = highTemp,
    lowTemp = lowTemp,
    condition = condition,
    iconToken = nativeDailyIconToken,
    precipProbability = precipProbability,
    precipAmountMm = precipAmountMm,
    isClimateNormal = isClimateNormal,
    source = source,
    daytimePrecipProbability = daytimePrecipProbability,
    nighttimePrecipProbability = nighttimePrecipProbability,
    noonCloudPercent = noonCloudPercent,
    hourlyDayPrecipMax = hourlyDayPrecipMax,
    hourlyNightPrecipMax = hourlyNightPrecipMax,
    hourlyDayPrecipPeakAt = hourlyDayPrecipPeakAt,
    fogLevel = fogLevel,
    fogTopTemp = fogTopTemp,
    fogVisibilityMi = fogVisibilityMi,
    fogPredawn = fogPredawn,
    fetchedAt = fetchedAt,
    lastConfirmedAt = lastConfirmedAt,
)

/** This row as one of a day's older fetches for the shared daily model. */
fun ForecastEntity.toDailyForecastSnapshot(date: java.time.LocalDate) = com.weatherwidget.data.model.DailyForecastSnapshot(
    date = date.toString(),
    highTemp = highTemp,
    lowTemp = lowTemp,
    condition = condition,
    iconToken = nativeDailyIconToken,
    precipProbability = precipProbability,
    precipAmountMm = precipAmountMm,
    fetchedAt = fetchedAt,
    daytimePrecipProbability = daytimePrecipProbability,
    nighttimePrecipProbability = nighttimePrecipProbability,
    hindcastHighTemp = hindcastHighTemp,
    hindcastLowTemp = hindcastLowTemp,
    // The dedup skip re-stamps lastConfirmedAt; fetchedAt stays the first sighting.
    lastConfirmedAt = lastConfirmedAt,
)
