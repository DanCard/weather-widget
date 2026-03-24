package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ObservationBlenderTest {

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
    private val center = LocalDateTime.of(2026, 2, 20, 12, 0)

    private fun wideForecasts(): List<HourlyForecastEntity> {
        val start = center.minusHours(24)
        val end = center.plusHours(72)
        val result = mutableListOf<HourlyForecastEntity>()
        var cur = start
        while (!cur.isAfter(end)) {
            result.add(TestData.hourly(dateTime = cur.format(fmt), temperature = 60f + cur.hour))
            cur = cur.plusHours(1)
        }
        return result
    }

    @Test
    fun `resolveCurrentObservation blends and extrapolates`() {
        val forecasts = wideForecasts()
        
        // Latest observation is 30 mins ago
        val now = center
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val obsTime = nowMs - 30 * 60 * 1000L
        
        val observations = listOf(
            TestData.observation(stationId = "STATION_A", timestamp = obsTime, temperature = 70f, distanceKm = 5f)
        )

        val resolved = ObservationBlender.resolveCurrentObservation(
            observations = observations,
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            now = now
        )

        assertNotNull(resolved)
        // Forecast at center (12:00) is 60 + 12 = 72
        // Forecast at obsTime (11:30) is interp between 11:00 (71) and 12:00 (72) = 71.5
        // Forecast delta = 72 - 71.5 = 0.5
        // Extrapolated temp = 70 + 0.5 = 70.5
        assertEquals(70.5f, resolved!!.first, 0.1f)
        assertEquals(nowMs, resolved.second)
    }

    @Test
    fun `resolveCurrentObservation returns anchor timestamp for staleness`() {
        val forecasts = wideForecasts()
        val now = center
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val anchorTs = nowMs - 45 * 60 * 1000L // 45 mins ago
        
        // Station reported 45 mins ago, we are now at 'center' (extrapolating)
        val observations = listOf(
            TestData.observation(stationId = "STATION_A", timestamp = anchorTs, temperature = 70f, distanceKm = 5f)
        )

        val resolved = ObservationBlender.resolveCurrentObservation(
            observations = observations,
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            now = now
        )

        assertNotNull(resolved)
        // first: temperature (extrapolated)
        // second: timestamp (target time, close to 'now')
        // third: anchorTimestamp (the original 45 min old reading)
        assertEquals(nowMs, resolved!!.second)
        assertEquals(anchorTs, resolved.third)
        
        // Staleness should be 45 mins
        val ageMin = java.time.Duration.between(
            java.time.Instant.ofEpochMilli(resolved.third).atZone(ZoneId.systemDefault()).toLocalDateTime(),
            now
        ).toMinutes()
        assertEquals(45L, ageMin)
    }

    @Test
    fun `interpolated points use later observation as anchor`() {
        val forecasts = wideForecasts()
        val startObs = center.minusMinutes(30)
        val endObs = center.plusMinutes(30) // Observation in the "future" relative to center
        val startObsMs = startObs.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endObsMs = endObs.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        
        val observations = listOf(
            TestData.observation(stationId = "S1", timestamp = startObsMs, temperature = 60f),
            TestData.observation(stationId = "S1", timestamp = endObsMs, temperature = 70f)
        )

        // Blend for a window including 'center' (which is between start and end)
        val result = ObservationBlender.blendObservationSeries(
            observations = observations,
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = startObsMs,
            endMs = endObsMs
        )

        // Find the point at 'center' (interpolated)
        val centerMs = center.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val interpolated = result.observations.find { it.timestamp in (centerMs - 60000)..(centerMs + 60000) }
        
        assertNotNull("Should find interpolated point near center", interpolated)
        assertEquals("interpolated", interpolated!!.condition)
        // Anchor should be the LATER observation (endObsMs)
        assertEquals(endObsMs, interpolated.fetchedAt)
    }
}
