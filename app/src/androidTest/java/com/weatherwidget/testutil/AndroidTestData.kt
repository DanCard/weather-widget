package com.weatherwidget.testutil

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Factory methods for creating test entities in instrumented tests.
 * Complements the unit test TestData utility.
 */
object AndroidTestData {
    const val LAT = 37.42
    const val LON = -122.08

    fun createObservation(
        stationId: String,
        timestamp: Long,
        temperature: Float,
        distanceKm: Float
    ) = ObservationEntity(
        stationId = stationId,
        stationName = "Station $stationId",
        timestamp = timestamp,
        temperature = temperature,
        condition = "Clear",
        locationLat = LAT,
        locationLon = LON,
        distanceKm = distanceKm,
        stationType = "OFFICIAL",
        fetchedAt = System.currentTimeMillis(),
        api = "NWS"
    )

    fun createHourly(
        dateTime: String,
        temperature: Float
    ) = HourlyForecastEntity(
        dateTime = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        locationLat = LAT,
        locationLon = LON,
        temperature = temperature,
        condition = "Sunny",
        source = "NWS",
        precipProbability = 0,
        fetchedAt = System.currentTimeMillis()
    )
}
