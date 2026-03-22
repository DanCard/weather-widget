package com.weatherwidget.testutil

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity

/**
 * Factory methods for creating test entities with sensible defaults.
 * All location defaults match (37.42, -122.08) so DAO proximity queries work.
 */
object TestData {
    const val LAT = 37.42
    const val LON = -122.08
    const val LOCATION_NAME = "Test Location"

    fun forecast(
        targetDate: String = "2026-02-20",
        forecastDate: String = "2026-02-20",
        source: String = "NWS",
        highTemp: Float? = 65f,
        lowTemp: Float? = 45f,
        condition: String = "Sunny",
        isClimateNormal: Boolean = false,
        precipProbability: Int? = null,
        fetchedAt: Long = System.currentTimeMillis(),
        lat: Double = LAT,
        lon: Double = LON,
    ) = ForecastEntity(
        targetDate = targetDate,
        forecastDate = forecastDate,
        locationLat = lat,
        locationLon = lon,
        locationName = LOCATION_NAME,
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = condition,
        isClimateNormal = isClimateNormal,
        source = source,
        precipProbability = precipProbability,
        fetchedAt = fetchedAt,
    )

    fun observation(
        stationId: String = "KSFO",
        stationName: String = "San Francisco Intl",
        timestamp: Long = System.currentTimeMillis(),
        temperature: Float = 62f,
        condition: String = "Clear",
        lat: Double = LAT,
        lon: Double = LON,
        distanceKm: Float = 5f,
        stationType: String = "OFFICIAL",
        fetchedAt: Long = System.currentTimeMillis(),
        api: String = "NWS",
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationName,
        timestamp = timestamp,
        temperature = temperature,
        condition = condition,
        locationLat = lat,
        locationLon = lon,
        distanceKm = distanceKm,
        stationType = stationType,
        fetchedAt = fetchedAt,
        api = api,
    )

    fun hourly(
        dateTime: String = "2026-02-20T14:00",
        source: String = "NWS",
        temperature: Float = 60f,
        condition: String = "Sunny",
        precipProbability: Int? = null,
        fetchedAt: Long = System.currentTimeMillis(),
        lat: Double = LAT,
        lon: Double = LON,
    ) = HourlyForecastEntity(
        dateTime = toEpoch(dateTime),
        locationLat = lat,
        locationLon = lon,
        temperature = temperature,
        condition = condition,
        source = source,
        precipProbability = precipProbability,
        fetchedAt = fetchedAt,
    )

    fun toEpoch(dateTime: String): Long {
        return java.time.LocalDateTime.parse(dateTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
