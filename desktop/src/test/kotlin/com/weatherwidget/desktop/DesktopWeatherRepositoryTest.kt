package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.model.HourlyForecast
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopWeatherRepositoryTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-test-repo", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        
        // Pass dummy weatherService since loadCached does not make network calls
        val dummyService = DesktopWeatherService(37.4220, -122.0841, "NWS")
        repository = DesktopWeatherRepository(dummyService, dao, 37.4220, -122.0841, "NWS")
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `loadCached resolves correct temperature delta`() = runTest {
        try {
            val baseTime = System.currentTimeMillis()
            val now = (baseTime / 3600_000L) * 3600_000L

            // 1. Insert hourly forecasts
            val hourly = listOf(
                HourlyForecast(now - 3600_000L, 70f, "Clear"), // 1 hour ago
                HourlyForecast(now, 72f, "Clear"),             // Now
                HourlyForecast(now + 3600_000L, 74f, "Clear"), // 1 hour from now
            )
            dao.upsertHourlyForecasts(37.4220, -122.0841, "NWS", hourly)

            // 2. Insert observation matching 1 hour ago
            // Observation says 73.3 degrees, but forecast 1 hour ago was 70.0 degrees.
            // So the raw delta is +3.3 degrees.
            val obs = listOf(
                DesktopObservationEntity(
                    stationId = "STATION_A",
                    stationName = "Station A",
                    timestamp = now - 3600_000L,
                    temperature = 73.3f,
                    condition = "Clear",
                    locationLat = 37.4220,
                    locationLon = -122.0841,
                    distanceKm = 0f,
                    stationType = "VIRTUAL",
                    fetchedAt = now,
                    api = "NWS"
                )
            )
            dao.upsertObservations(obs)

            // 3. Load cache
            val result = repository.loadCached()
            assertNotNull(result)

            val elapsedMs = System.currentTimeMillis() - now
            val fraction = elapsedMs.toFloat() / 3600_000f
            val expectedForecast = 72f + (74f - 72f) * fraction
            val expectedTemp = expectedForecast + 3.3f

            println("DIAGNOSTIC: currentTemp=${result!!.currentTemp} appliedDelta=${result.appliedDelta} expectedTemp=$expectedTemp")

            // 4. Verify display temperature and applied delta
            assertEquals(expectedTemp, result.currentTemp!!, 0.05f)
            assertEquals(3.3f, result.appliedDelta!!, 0.01f)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `loadCached fills missing cloud cover from hourly history`() = runTest {
        val baseTime = System.currentTimeMillis()
        val now = (baseTime / 3600_000L) * 3600_000L

        val liveRows = listOf(
            HourlyForecast(
                dateTime = now - 3600_000L,
                temperature = 70f,
                condition = "Clear",
                cloudCover = null,
                source = "NWS",
                fetchedAt = now,
            ),
            HourlyForecast(
                dateTime = now + 3600_000L,
                temperature = 72f,
                condition = "Sunny",
                cloudCover = 14,
                source = "NWS",
                fetchedAt = now,
            ),
        )
        val historyRows = listOf(
            HourlyForecast(
                dateTime = now - 3600_000L,
                temperature = 68f,
                condition = "Cloudy",
                cloudCover = 82,
                source = "NWS",
                fetchedAt = now - 10_000L,
            ),
        )

        dao.upsertHourlyForecasts(37.4220, -122.0841, "NWS", liveRows)
        dao.upsertHourlyForecastHistory(37.4220, -122.0841, "NWS", now - 4 * 3600_000L, historyRows)

        val result = repository.loadCached()
        assertNotNull(result)

        val merged = result!!.hourly.associateBy { it.dateTime }
        val repaired = merged[now - 3600_000L]!!
        assertEquals(82, repaired.cloudCover)
        assertEquals(70f, repaired.temperature, 0.0f)
        assertEquals("Clear", repaired.condition)
        assertEquals(14, merged[now + 3600_000L]!!.cloudCover)
    }
}
