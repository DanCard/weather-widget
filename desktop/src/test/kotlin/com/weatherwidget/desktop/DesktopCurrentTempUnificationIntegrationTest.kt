package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.widget.CurrentTemperatureResolver
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class DesktopCurrentTempUnificationIntegrationTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val testLat = 45.0
    private val testLon = -93.0

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("desktop-temp-unification-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        
        val dummyService = DesktopWeatherService(testLat, testLon, WeatherSource.OPEN_METEO.id)
        repository = DesktopWeatherRepository(dummyService, dao, testLat, testLon, WeatherSource.OPEN_METEO.id)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `Desktop current temperature resolution uses unified shared logic`() = runTest {
        val zoneId = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 6, 8, 12, 15) // 12:15 PM
        val nowMs = now.atZone(zoneId).toInstant().toEpochMilli()

        // 1. Setup large window of hourly forecasts (up to 10 days, like desktop cache)
        val largeWindow = mutableListOf<HourlyForecast>()
        for (i in -48..48) { // 4 days of data
            val dt = now.plusHours(i.toLong()).withMinute(0).withSecond(0).withNano(0)
            largeWindow.add(
                HourlyForecast(
                    dateTime = dt.atZone(zoneId).toInstant().toEpochMilli(),
                    temperature = 70f + (i * 0.5f), // Gradual curve
                    condition = "Sunny",
                    source = WeatherSource.OPEN_METEO.id,
                    fetchedAt = nowMs
                )
            )
        }
        dao.upsertHourlyForecasts(testLat, testLon, WeatherSource.OPEN_METEO.id, largeWindow)

        // 2. Setup a recent observation 15 minutes ago
        val observationTimeMs = nowMs - (15 * 60 * 1000L)
        val obs = listOf(
            DesktopObservationEntity(
                stationId = "STATION_RECENT",
                stationName = "Recent Station",
                timestamp = observationTimeMs,
                temperature = 85.0f, // Significant jump to make it obvious if it's used
                condition = "Sunny",
                locationLat = testLat,
                locationLon = testLon,
                distanceKm = 0f,
                stationType = "VIRTUAL",
                fetchedAt = nowMs,
                api = WeatherSource.OPEN_METEO.id
            )
        )
        dao.upsertObservations(obs)
        
        // 3. Insert dummy daily data to ensure loadCached returns non-null
        val daily = listOf(
            com.weatherwidget.data.model.DailyForecast(
                date = now.toLocalDate().toString(),
                highTemp = 80f,
                lowTemp = 60f,
                condition = "Sunny"
            )
        )
        dao.upsertForecasts(testLat, testLon, WeatherSource.OPEN_METEO.id, daily)

        // 4. Perform resolution via repository
        val result = repository.loadCached(nowMs)
        assertNotNull("Repository loadCached returned null unexpectedly", result)

        // 5. Manual resolution using shared logic directly to verify consistency
        val sharedWindow = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
        val minEpoch = sharedWindow.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxEpoch = sharedWindow.end.atZone(zoneId).toInstant().toEpochMilli()
        
        val narrowHourly = largeWindow.filter { it.dateTime in minEpoch..maxEpoch }
        val narrowObs = obs.filter { it.timestamp in minEpoch..maxEpoch }.map { it.toReading() }

        val resolvedObs = ActualsAggregator.resolveCurrentObservation(
            observations = narrowObs,
            hourlyForecasts = narrowHourly,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            userLat = testLat,
            userLon = testLon,
            nowMs = nowMs,
            lookbackHours = 12L,
            lookaheadHours = 3L,
        )
        assertNotNull("Recent observation should have been resolved", resolvedObs)

        val (resolvedTemp, resolvedObsAt, _) = resolvedObs!!

        val smoothedForecasts = CurrentTemperatureResolver.computeSmoothedForecasts(
            narrowHourly, WeatherSource.OPEN_METEO.id
        )

        val expectedResolution = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.OPEN_METEO,
            hourlyForecasts = narrowHourly,
            lastObservedTemp = resolvedTemp,
            observedAt = resolvedObsAt,
            storedDeltaState = null,
            currentLat = testLat,
            currentLon = testLon,
            smoothedForecasts = smoothedForecasts
        )

        // 6. Assert that repository resolution matches direct shared logic resolution
        assertEquals(
            "Desktop repository must resolve current temperature identically to the shared resolver",
            expectedResolution.displayTemp!!,
            result!!.currentTemp!!,
            0.01f
        )
    }
}
