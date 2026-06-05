package com.weatherwidget.desktop

import com.weatherwidget.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RefreshDelayTest {

    @Test
    fun `returns default delay for null hourly`() {
        assertEquals(15 * 60 * 1000L, computeRefreshDelayMs(null))
    }

    @Test
    fun `returns default delay for empty hourly`() {
        assertEquals(15 * 60 * 1000L, computeRefreshDelayMs(emptyList()))
    }

    @Test
    fun `returns shorter delay for high temperature swing`() {
        val hourly = listOf(
            HourlyForecast(0, 60f, "Clear"),
            HourlyForecast(3600000, 72f, "Clear"), // 12 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        // getUpdatesPerHour returns 4 for maxDiff >= 8, so 3600000/4 = 900000 (15 min)
        assertTrue(delay >= 10 * 60 * 1000L)
        assertTrue(delay <= 15 * 60 * 1000L)
    }

    @Test
    fun `returns default delay for low temperature swing`() {
        val hourly = listOf(
            HourlyForecast(0, 70f, "Clear"),
            HourlyForecast(3600000, 71f, "Clear"), // 1 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        assertEquals(60 * 60 * 1000L, delay) // 1 update per hour
    }

    @Test
    fun `respects minimum delay floor`() {
        // Even with extreme swing, delay should be >= 10 min
        val hourly = listOf(
            HourlyForecast(0, 40f, "Clear"),
            HourlyForecast(3600000, 80f, "Clear"), // 40 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        assertTrue(delay >= 10 * 60 * 1000L)
    }

    @Test
    fun `launch refresh uses full forecast when cache is missing`() {
        val action = determineLaunchRefreshAction(
            cachePresent = false,
            lastObservationFetchMs = 900_000L,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.FULL_FORECAST, action)
    }

    @Test
    fun `launch refresh skips network when cached observations are fresh`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 500_000L,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.NONE, action)
    }

    @Test
    fun `launch refresh uses observations only when cached observations are stale`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 300_000L,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.OBSERVATIONS, action)
    }

    @Test
    fun `launch refresh uses observations only when cached observation fetch is unknown`() {
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = null,
            nowMs = 1_000_000L,
        )

        assertEquals(LaunchRefreshAction.OBSERVATIONS, action)
    }

    @Test
    fun `observations only refresh skips unsupported current-only sources`() = runTest {
        val service = DesktopWeatherService(
            latitude = 37.4220,
            longitude = -122.0841,
            weatherSource = WeatherSource.TOMORROW_IO.id,
        )

        try {
            val result = service.fetchObservationsOnly()

            assertNull(result.currentTemp)
            assertTrue(result.daily.isEmpty())
            assertTrue(result.hourly.isEmpty())
            assertTrue(result.rawObservations.isEmpty())
        } finally {
            service.close()
        }
    }

    @Test
    fun `recalculateCurrentTemp uses fresh observation when available`() {
        val now = 1000L * 60 * 60
        val forecast = ForecastResult(
            hourly = listOf(HourlyForecast(now, 70f, "Sunny")),
            rawObservations = listOf(
                ObservationReading(
                    stationId = "NWS_BLEND",
                    stationName = "Blend Station",
                    timestamp = now - 1000 * 60 * 10, // 10 mins ago (fresh)
                    temperature = 72f,
                    condition = "Mostly Sunny",
                    locationLat = 37.4,
                    locationLon = -122.0,
                    api = "NWS"
                )
            )
        )
        val (temp, cond) = recalculateCurrentTemp(forecast, now)
        assertEquals(72f, temp)
        assertEquals("Mostly Sunny", cond)
    }

    @Test
    fun `recalculateCurrentTemp uses interpolated temp when observations are stale`() {
        val zoneId = java.time.ZoneId.systemDefault()
        val now = 1000L * 60 * 60 * 24 * 10 // 10 days from epoch
        
        // Force testNow to be at minute 30
        val testNow = java.time.Instant.ofEpochMilli(now).atZone(zoneId)
            .withMinute(30).withSecond(0).withNano(0)
            .toInstant().toEpochMilli()
            
        val targetHour = java.time.Instant.ofEpochMilli(testNow).atZone(zoneId).toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val nextHour = targetHour.plusHours(1)
        val targetHourMs = targetHour.atZone(zoneId).toInstant().toEpochMilli()
        val nextHourMs = nextHour.atZone(zoneId).toInstant().toEpochMilli()

        val forecast = ForecastResult(
            hourly = listOf(
                HourlyForecast(targetHourMs, 60f, "Sunny"),
                HourlyForecast(nextHourMs, 80f, "Sunny")
            ),
            rawObservations = listOf(
                ObservationReading(
                    stationId = "NWS_BLEND",
                    stationName = "Blend Station",
                    timestamp = testNow - 1000 * 60 * 40, // 40 mins ago (stale, threshold is 30 mins)
                    temperature = 50f,
                    condition = "Cloudy",
                    locationLat = 37.4,
                    locationLon = -122.0,
                    api = "NWS"
                )
            )
        )
        val (temp, cond) = recalculateCurrentTemp(forecast, testNow)
        assertEquals(70f, temp!!, 0.1f)
        assertEquals("Sunny", cond)
    }

    @Test
    fun `recalculateCurrentTemp falls back to first hourly if interpolation fails`() {
        val now = 1000L * 60 * 60
        val forecast = ForecastResult(
            hourly = listOf(HourlyForecast(now + 1000L * 60 * 120, 75f, "Sunny")),
            rawObservations = emptyList()
        )
        val (temp, cond) = recalculateCurrentTemp(forecast, now)
        assertEquals(75f, temp)
        assertEquals("Sunny", cond)
    }
}

