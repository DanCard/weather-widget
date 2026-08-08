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
    tableName = "daily_history",
    primaryKeys = ["date", "source", "locationLat", "locationLon"],
    indices = [
        Index(value = ["date", "locationLat", "locationLon"]),
    ],
)
data class DailyHistoryEntity(
    val date: Long,             // UTC midnight epoch millis (e.g., 2026-03-18T00:00:00Z)
    val source: String,         // WeatherSource.id (NWS, OPEN_METEO, etc.)
    val locationLat: Double,
    val locationLon: Double,
    val computedHighTemp: Float, // °F — blended extreme from IDW observation pipeline ("Location actual")
    val computedLowTemp: Float,  // °F — blended extreme
    val condition: String,
    val updatedAt: Long,        // epoch ms, used for cleanup
    val precipAmountMm: Float? = null, // Daily observed precipitation amount in mm (total)
    val precipDayMm: Float? = null, // Daytime (8AM-8PM) observed precipitation in mm
    val precipNightMm: Float? = null, // Nighttime (8PM-8AM) observed precipitation in mm
    // Resolved (as-displayed) forecast rain chance %, snapshotted while the day was current so
    // history replays what the widget showed instead of NWS's raw 6am/6pm period fields (see
    // DailyRainLabels.resolveDailyLabelPrecip). Null for rows written before this feature.
    val forecastDayPrecipChance: Int? = null,
    val forecastNightPrecipChance: Int? = null,
    // Frozen forecast-overlay values (yellow accuracy bar) and noon cloud %, snapshotted while the
    // day was current so the daily bar view can render past days from this row alone (see
    // DailyHistoryFreeze). High/low move as a unit. Null for rows written before this feature.
    val forecastHighTemp: Float? = null,
    val forecastLowTemp: Float? = null,
    val forecastPrecipAmountMm: Float? = null,
    val noonCloudPercent: Int? = null,
    val apiHighTemp: Float? = null,  // °F — API-reported observed high; null when source provides no native actuals
    val apiLowTemp: Float? = null,   // °F — API-reported observed low; null when source provides no native actuals
    // Which station produced apiHighTemp/apiLowTemp, when they came from station observations
    // (NWS via StationDailyExtremes). Null for sources whose api actuals are a gridded product
    // (Open-Meteo ERA5) and for rows written before v59.
    val apiStationId: String? = null,
    val apiStationDistanceKm: Float? = null,
    // Where the NWS actuals on this row came from — see DailyActualsSource. Doubles as the marker
    // that a past day has been resolved, which is what freezes its blend against later recomputes.
    // Null for non-NWS rows and for rows written before v60.
    val actualsSource: String? = null,
    // Which code path last wrote this row — see DailyHistoryWriter. Diagnostic only.
    val lastWriter: String? = null,
)

fun DailyHistoryEntity.toDailyHistory() = com.weatherwidget.data.model.DailyHistory(
    date = date,
    source = source,
    locationLat = locationLat,
    locationLon = locationLon,
    computedHighTemp = computedHighTemp,
    computedLowTemp = computedLowTemp,
    condition = condition,
    updatedAt = updatedAt,
    precipAmountMm = precipAmountMm,
    precipDayMm = precipDayMm,
    precipNightMm = precipNightMm,
    forecastDayPrecipChance = forecastDayPrecipChance,
    forecastNightPrecipChance = forecastNightPrecipChance,
    forecastHighTemp = forecastHighTemp,
    forecastLowTemp = forecastLowTemp,
    forecastPrecipAmountMm = forecastPrecipAmountMm,
    noonCloudPercent = noonCloudPercent,
    apiHighTemp = apiHighTemp,
    apiLowTemp = apiLowTemp,
    apiStationId = apiStationId,
    apiStationDistanceKm = apiStationDistanceKm,
    actualsSource = actualsSource,
    lastWriter = lastWriter,
)

fun com.weatherwidget.data.model.DailyHistory.toEntity() = DailyHistoryEntity(
    date = date,
    source = source,
    locationLat = locationLat,
    locationLon = locationLon,
    computedHighTemp = computedHighTemp,
    computedLowTemp = computedLowTemp,
    condition = condition,
    updatedAt = updatedAt,
    precipAmountMm = precipAmountMm,
    precipDayMm = precipDayMm,
    precipNightMm = precipNightMm,
    forecastDayPrecipChance = forecastDayPrecipChance,
    forecastNightPrecipChance = forecastNightPrecipChance,
    forecastHighTemp = forecastHighTemp,
    forecastLowTemp = forecastLowTemp,
    forecastPrecipAmountMm = forecastPrecipAmountMm,
    noonCloudPercent = noonCloudPercent,
    apiHighTemp = apiHighTemp,
    apiLowTemp = apiLowTemp,
    apiStationId = apiStationId,
    apiStationDistanceKm = apiStationDistanceKm,
    actualsSource = actualsSource,
    lastWriter = lastWriter,
)
