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
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
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
    fun `resolveCurrentObservation returns latest real anchor point instead of extrapolating`() {
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
        // Should return the RAW observation at 70.0, NOT the extrapolated 70.5
        assertEquals(70.0f, resolved!!.first, 0.1f)
        assertEquals(obsTime, resolved.second)
    }

    @Test
    fun `resolveCurrentObservation returns anchor timestamp for both time and anchorTime`() {
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
        // first: temperature (anchor)
        // second: timestamp (anchor)
        // third: anchorTimestamp (anchor)
        assertEquals(anchorTs, resolved!!.second)
        assertEquals(anchorTs, resolved.third)
        
        // Staleness relative to 'now' should be 45 mins
        val ageMin = java.time.Duration.between(
            java.time.Instant.ofEpochMilli(resolved.second).atZone(ZoneId.systemDefault()).toLocalDateTime(),
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
        // With observation-driven timestamps, candidate times are startObsMs and endObsMs only.
        // No synthetic intermediate points are emitted — only real observation timestamps.
        val result = ObservationBlender.blendObservationSeries(
            observations = observations,
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = startObsMs,
            endMs = endObsMs
        )

        // Candidate times are the two real observation timestamps
        assertEquals(2, result.stats.candidateTimeCount)
        assertEquals(2, result.observations.size)

        val startPoint = result.observations.find { it.timestamp == startObsMs }
        val endPoint = result.observations.find { it.timestamp == endObsMs }
        assertNotNull("Should have point at start observation time", startPoint)
        assertNotNull("Should have point at end observation time", endPoint)
        assertEquals("observed", startPoint!!.condition)
        assertEquals("observed", endPoint!!.condition)
        assertEquals(60f, startPoint.temperature, 0.01f)
        assertEquals(70f, endPoint.temperature, 0.01f)
    }

    @Test
    fun `blend is consistent across different startMs windows`() {
        val forecasts = wideForecasts()
        val now = center
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        
        // S1 is close (2km), reports at T-4h
        // S2 is further (10km), reports at T-1h
        // We want to check the blend at T-1h.
        val tMinus4h = nowMs - 4 * 60 * 60 * 1000L
        val tMinus1h = nowMs - 1 * 60 * 60 * 1000L
        
        val observations = listOf(
            TestData.observation(stationId = "S1", timestamp = tMinus4h, temperature = 60f, distanceKm = 2f),
            TestData.observation(stationId = "S2", timestamp = tMinus1h, temperature = 70f, distanceKm = 10f)
        )

        // Wide window (8h back) includes S1 (T-4h)
        val wideStartMs = nowMs - 8 * 60 * 60 * 1000L
        val wideResult = ObservationBlender.blendObservationSeries(
            observations = observations,
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = wideStartMs,
            endMs = nowMs
        )
        
        // Narrow window (2h back) excludes S1 (T-4h) from the emitted result, 
        // but it should still be used for blending the point at T-1h!
        val narrowStartMs = nowMs - 2 * 60 * 60 * 1000L
        val narrowResult = ObservationBlender.blendObservationSeries(
            observations = observations,
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = narrowStartMs,
            endMs = nowMs
        )

        val widePointAtTMinus1h = wideResult.observations.find { it.timestamp == tMinus1h }
        val narrowPointAtTMinus1h = narrowResult.observations.find { it.timestamp == tMinus1h }

        assertNotNull("Wide result should have point at T-1h", widePointAtTMinus1h)
        assertNotNull("Narrow result should have point at T-1h", narrowPointAtTMinus1h)
        
        // Without the fix, narrowPointAtTMinus1h would be 70.0 (only S2), 
        // while widePointAtTMinus1h would be a blend of S1 (extrapolated) and S2.
        assertEquals("Blend should be consistent across zoom levels", 
            widePointAtTMinus1h!!.temperature, 
            narrowPointAtTMinus1h!!.temperature, 
            0.01f
        )
        
        // Verify that it's actually a blend and not just 70.0
        assertTrue("Temperature should be a blend (not exactly 70.0)", 
            Math.abs(narrowPointAtTMinus1h.temperature - 70.0f) > 0.1f
        )
    }
}
