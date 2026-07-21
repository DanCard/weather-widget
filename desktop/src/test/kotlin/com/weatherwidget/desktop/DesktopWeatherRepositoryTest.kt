package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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
        // Past hour shows the LATEST forecast: the live row wins for temp/condition, and history only
        // backfills the cloudCover the live row was missing — see HourlyForecastStitcher.
        val repaired = merged[now - 3600_000L]!!
        assertEquals(82, repaired.cloudCover) // backfilled from history (live had null)
        assertEquals(70f, repaired.temperature, 0.0f) // live forecast, not the history snapshot
        assertEquals("Clear", repaired.condition)
        // Future hour keeps the live forecast.
        assertEquals(14, merged[now + 3600_000L]!!.cloudCover)
        assertEquals(72f, merged[now + 3600_000L]!!.temperature, 0.0f)
    }

    @Test
    fun `loadCached fills future gap days with climate normals`() = runTest {
        val today = LocalDate.now()

        // Real forecast covers only today..today+2.
        val realForecast = (0..2).map { offset ->
            val d = today.plusDays(offset.toLong())
            DailyForecast(date = d.toString(), highTemp = 70f + offset, lowTemp = 50f + offset, condition = "Clear")
        }
        dao.upsertForecasts(37.4220, -122.0841, "NWS", realForecast)

        // Cached climate normals (distinct per month so values are recognizable).
        val monthlyHigh = (1..12).associateWith { (it * 5 + 40).toFloat() }
        val monthlyLow = (1..12).associateWith { (it * 5 + 20).toFloat() }
        dao.upsertClimateNormals(ClimateNormals.locationKey(37.4220, -122.0841), monthlyHigh, monthlyLow)

        val daily = repository.loadCached()!!.daily
        val byDate = daily.associateBy { LocalDate.parse(it.date) }

        // Real forecast days are untouched (not climate normals).
        assertFalse(byDate[today]!!.isClimateNormal)
        assertFalse(byDate[today.plusDays(2)]!!.isClimateNormal)
        // The gap beyond real coverage is filled with climate normals...
        val gapDay = today.plusDays(6)
        assertTrue("expected a climate-normal row at $gapDay", byDate[gapDay]?.isClimateNormal == true)
        // ...and its value matches the expanded monthly normal for that calendar day.
        val expected = ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)[java.time.MonthDay.from(gapDay)]!!
        assertEquals(expected.first, byDate[gapDay]!!.highTemp, 0.001f)
    }

    @Test
    fun `cleanup preserves data within 18 months and deletes older data`() = runTest {
        val now = System.currentTimeMillis()
        val dayMs = 24 * 3600 * 1000L
        val recentTime = now - (100 * dayMs) // 100 days old (within 18 months)
        val boundaryTime = now - (500 * dayMs) // 500 days old (within 18 months)
        val oldTime = now - (600 * dayMs) // 600 days old (> 547 days / 18 months)

        val obs = listOf(
            DesktopObservationEntity(
                stationId = "STATION_100", stationName = "Station 100", timestamp = recentTime,
                temperature = 70f, condition = "Clear", locationLat = 37.4220, locationLon = -122.0841,
                fetchedAt = recentTime, api = "NWS"
            ),
            DesktopObservationEntity(
                stationId = "STATION_500", stationName = "Station 500", timestamp = boundaryTime,
                temperature = 65f, condition = "Cloudy", locationLat = 37.4220, locationLon = -122.0841,
                fetchedAt = boundaryTime, api = "NWS"
            ),
            DesktopObservationEntity(
                stationId = "STATION_600", stationName = "Station 600", timestamp = oldTime,
                temperature = 60f, condition = "Rain", locationLat = 37.4220, locationLon = -122.0841,
                fetchedAt = oldTime, api = "NWS"
            )
        )
        dao.upsertObservations(obs)

        // Trigger cleanup using the 18-month (547-day) cutoff
        dao.cleanup(now - (547L * dayMs))

        val remaining = dao.getObservationsInRange(now - (700 * dayMs), now, 37.4220, -122.0841)
        val remainingStations = remaining.map { it.stationId }.toSet()

        assertTrue("100-day-old observation should be retained", remainingStations.contains("STATION_100"))
        assertTrue("500-day-old observation should be retained", remainingStations.contains("STATION_500"))
        assertFalse("600-day-old observation should be deleted by 18-month cutoff", remainingStations.contains("STATION_600"))
    }

    @Test
    fun `resolveCurrentTempInMemory returns exact same temperature as loadCached`() = runTest {
        val now = System.currentTimeMillis()
        val hourly = listOf(
            HourlyForecast(now - 3600_000L, 70f, "Clear"),
            HourlyForecast(now, 72f, "Clear"),
            HourlyForecast(now + 3600_000L, 74f, "Clear"),
        )
        dao.upsertHourlyForecasts(37.4220, -122.0841, "NWS", hourly)

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

        val daily = listOf(
            DailyForecast(LocalDate.now().toString(), 74f, 68f, "Clear", "NWS")
        )
        dao.upsertForecasts(37.4220, -122.0841, "NWS", daily)

        val cachedResult = repository.loadCached(now)
        assertNotNull(cachedResult)

        val inMemoryResult = repository.resolveCurrentTempInMemory(cachedResult!!, now)
        assertNotNull(inMemoryResult)
        assertEquals(cachedResult.currentTemp, inMemoryResult.first)
        assertEquals(cachedResult.appliedDelta, inMemoryResult.second)
    }
}


