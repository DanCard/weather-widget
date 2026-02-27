package com.weatherwidget.testutil

import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity

/**
 * Factory methods for creating test entities with sensible defaults.
 * All location defaults match (37.42, -122.08) so DAO proximity queries work.
 */
object TestData {
    const val LAT = 37.42
    const val LON = -122.08
    const val LOCATION_NAME = "Test Location"

    fun weather(
        date: String = "2026-02-20",
        source: String = "NWS",
        highTemp: Float? = 65f,
        lowTemp: Float? = 45f,
        condition: String = "Sunny",
        isActual: Boolean = false,
        isClimateNormal: Boolean = false,
        stationId: String? = null,
        precipProbability: Int? = null,
        fetchedAt: Long = System.currentTimeMillis(),
        lat: Double = LAT,
        lon: Double = LON,
    ) = WeatherEntity(
        date = date,
        locationLat = lat,
        locationLon = lon,
        locationName = LOCATION_NAME,
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = condition,
        isActual = isActual,
        isClimateNormal = isClimateNormal,
        source = source,
        stationId = stationId,
        precipProbability = precipProbability,
        fetchedAt = fetchedAt,
    )

    fun forecastSnapshot(
        targetDate: String = "2026-02-21",
        forecastDate: String = "2026-02-20",
        source: String = "NWS",
        highTemp: Float? = 68f,
        lowTemp: Float? = 48f,
        condition: String = "Partly Cloudy",
        fetchedAt: Long = System.currentTimeMillis(),
        lat: Double = LAT,
        lon: Double = LON,
    ) = ForecastSnapshotEntity(
        targetDate = targetDate,
        forecastDate = forecastDate,
        locationLat = lat,
        locationLon = lon,
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = condition,
        source = source,
        fetchedAt = fetchedAt,
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
        dateTime = dateTime,
        locationLat = lat,
        locationLon = lon,
        temperature = temperature,
        condition = condition,
        source = source,
        precipProbability = precipProbability,
        fetchedAt = fetchedAt,
    )
}
