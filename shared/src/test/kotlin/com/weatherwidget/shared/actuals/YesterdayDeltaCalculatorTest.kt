package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class YesterdayDeltaCalculatorTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun `delta is current minus the blended actual 24h earlier`() {
        // Observed now = 72 at 15:00 today; yesterday 15:00 had a single 68 observation.
        val now = epoch("2026-06-22T15:00:00")
        val obs = listOf(
            observation("S", "2026-06-21T15:00:00", 68f, distanceKm = 2f),
            observation("S", "2026-06-22T15:00:00", 72f, distanceKm = 2f),
        )
        val delta = YesterdayDeltaCalculator.computeDelta(
            observations = obs,
            hourlyForecasts = forecasts("2026-06-21T00:00:00", 48),
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            observedAtMs = now,
            currentObservedTemp = 72f,
            zoneId = zone,
        )
        assertEquals(4f, delta!!, 0.01f)
    }

    @Test
    fun `yesterday value is interpolated between bracketing observations`() {
        // 24h ago = 15:00; observations at 14:30 (66) and 15:30 (70) → interpolated 68 → delta 72-68=4.
        val now = epoch("2026-06-22T15:00:00")
        val obs = listOf(
            observation("S", "2026-06-21T14:30:00", 66f, distanceKm = 2f),
            observation("S", "2026-06-21T15:30:00", 70f, distanceKm = 2f),
            observation("S", "2026-06-22T15:00:00", 72f, distanceKm = 2f),
        )
        val delta = YesterdayDeltaCalculator.computeDelta(
            observations = obs,
            hourlyForecasts = forecasts("2026-06-21T00:00:00", 48),
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            observedAtMs = now,
            currentObservedTemp = 72f,
            zoneId = zone,
        )
        assertEquals(4f, delta!!, 0.01f)
    }

    @Test
    fun `null when no observation near the 24h-ago instant`() {
        // Only a yesterday-morning reading (08:00), far outside the ±90min window around 15:00.
        val now = epoch("2026-06-22T15:00:00")
        val obs = listOf(
            observation("S", "2026-06-21T08:00:00", 55f, distanceKm = 2f),
            observation("S", "2026-06-22T15:00:00", 72f, distanceKm = 2f),
        )
        val delta = YesterdayDeltaCalculator.computeDelta(
            observations = obs,
            hourlyForecasts = forecasts("2026-06-21T00:00:00", 48),
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            observedAtMs = now,
            currentObservedTemp = 72f,
            zoneId = zone,
        )
        assertNull(delta)
    }

    @Test
    fun `null when fetch-dot inputs missing`() {
        val obs = listOf(observation("S", "2026-06-21T15:00:00", 68f, distanceKm = 2f))
        val fc = forecasts("2026-06-21T00:00:00", 48)
        assertNull(
            YesterdayDeltaCalculator.computeDelta(obs, fc, WeatherSource.NWS.id, LAT, LON, null, 72f, zoneId = zone),
        )
        assertNull(
            YesterdayDeltaCalculator.computeDelta(obs, fc, WeatherSource.NWS.id, LAT, LON, epoch("2026-06-22T15:00:00"), null, zoneId = zone),
        )
    }

    private fun forecasts(start: String, count: Int, source: String = WeatherSource.NWS.id): List<HourlyForecast> {
        val startTime = LocalDateTime.parse(start)
        return (0..count).map { index ->
            HourlyForecast(
                dateTime = startTime.plusHours(index.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 60f + (index % 24),
                condition = "Clear",
                source = source,
            )
        }
    }

    private fun observation(
        stationId: String,
        time: String,
        temperature: Float,
        distanceKm: Float,
        api: String = WeatherSource.NWS.id,
    ): ObservationReading = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = epoch(time),
        temperature = temperature,
        condition = "observed",
        locationLat = LAT,
        locationLon = LON,
        distanceKm = distanceKm,
        api = api,
        stationType = "OFFICIAL",
    )

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
    }
}
