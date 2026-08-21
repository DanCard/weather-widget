package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "hourly_forecasts",
    primaryKeys = ["dateTime", "source", "locationLat", "locationLon"],
    indices = [Index(value = ["locationLat", "locationLon"])],
)
data class HourlyForecastEntity(
    val dateTime: Long, // Epoch ms
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float, // Temperature in Fahrenheit
    val condition: String, // Weather condition (e.g., "Cloudy", "Rain")
    val source: String, // Database storage: "NWS", "OPEN_METEO", or "Generic". Use WeatherSource.fromId() to convert.
    val precipProbability: Int? = null, // Precipitation probability percentage (0-100)
    val cloudCover: Int? = null, // Total-column cloud cover percentage (0-100)
    val precipAmountMm: Float? = null, // Hourly precipitation amount in millimeters
    val fetchedAt: Long, // When this data was fetched
    // Appended, not slotted next to cloudCover: this entity is built positionally in ~50 test files.
    val cloudCoverLow: Int? = null, // Low-layer only (0-100); what the cloud graph draws
)

fun HourlyForecastEntity.toHourlyForecast() = com.weatherwidget.data.model.HourlyForecast(
    dateTime = dateTime,
    temperature = temperature,
    condition = condition,
    precipProbability = precipProbability,
    cloudCover = cloudCover,
    cloudCoverLow = cloudCoverLow,
    precipAmountMm = precipAmountMm,
    source = source,
    fetchedAt = fetchedAt,
    locationLat = locationLat,
    locationLon = locationLon,
)

/**
 * Map a stitched [com.weatherwidget.data.model.HourlyForecast] back to an entity for the downstream
 * graph pipeline. Coordinates are always present (they came from a DB row) but fall back to the
 * query centre defensively.
 *
 * Shared by `GraphDataLoader` and `HourlyForecastLoader` deliberately: those two loaders reading the
 * same DB must produce identical rows, and a private per-loader copy of this conversion is exactly
 * the kind of drift that let a 13-day-old coordinate fragment win in one loader and lose in the
 * other (plans/260806-today-column-stale-fragment-delta-opus.md).
 */
fun com.weatherwidget.data.model.HourlyForecast.toEntity(
    fallbackLat: Double,
    fallbackLon: Double,
): HourlyForecastEntity =
    HourlyForecastEntity(
        dateTime = dateTime,
        locationLat = locationLat ?: fallbackLat,
        locationLon = locationLon ?: fallbackLon,
        temperature = temperature,
        condition = condition,
        source = source ?: com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
        precipProbability = precipProbability,
        cloudCover = cloudCover,
        cloudCoverLow = cloudCoverLow,
        precipAmountMm = precipAmountMm,
        fetchedAt = fetchedAt,
    )
