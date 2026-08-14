package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

@Category(MediumDuration::class)
class DesktopWeatherApiHistoryBackfillTest {
    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val service = mockk<DesktopWeatherService>()
    private val lat = 37.417
    private val lon = -122.089
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 7, 28)
    private val yesterday = today.minusDays(1)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weatherapi-history", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        repository =
            DesktopWeatherRepository(
                weatherService = service,
                weatherDao = dao,
                latitude = lat,
                longitude = lon,
                weatherSource = WeatherSource.WEATHER_API.id,
                currentTimeMillis = { now },
            )
        coEvery { service.fetchForecast() } returns currentForecast()
        coEvery { service.fetchHistoricalDailyTemps(any(), any()) } returns emptyList()
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `refresh backfills yesterday once and derives daily history`() = runTest {
        coEvery { service.fetchWeatherApiHistory(yesterday) } returns
            ForecastResult(rawObservations = historyReadings(24))

        repository.refresh(now)
        repository.refresh(now + 60_000L)

        val start = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = yesterday.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val observations =
            dao.getObservationsInRange(start, end, lat, lon)
                .filter { it.api == WeatherSource.WEATHER_API.id }
        val dailyKey = yesterday.toEpochDay() * 86_400_000L
        val actual =
            dao.getExtremesInRange(dailyKey, dailyKey, lat, lon)
                .firstOrNull { it.source == WeatherSource.WEATHER_API.id }

        assertEquals(24, observations.size)
        assertNotNull(actual)
        assertEquals(78f, actual!!.computedHighTemp, 0.001f)
        assertEquals(55f, actual.computedLowTemp, 0.001f)
        coVerify(exactly = 1) { service.fetchWeatherApiHistory(yesterday) }
    }

    @Test
    fun `history authorization failure keeps current forecast and enters cooldown`() = runTest {
        coEvery { service.fetchWeatherApiHistory(yesterday) } throws
            ApiAccessException(
                source = WeatherSource.WEATHER_API,
                statusCode = 403,
                detail = "forbidden",
                message = "forbidden",
            )

        val result = repository.refresh(now)

        assertFalse(result.raw.hourly.isEmpty())
        assertFalse(repository.needsDeeperHistory(48))
        val log =
            dao.getLatestLogByTagAndMessagePrefix(
                "WAPI_HISTORY_RESULT",
                "site=",
            )
        assertNotNull(log)
        assertTrue(log!!.message.contains("failure=auth_or_plan"))
        assertTrue(log.message.contains("retryAtMs="))
    }

    private fun currentForecast(): ForecastResult =
        ForecastResult(
            currentTemp = 70f,
            hourly =
                listOf(
                    HourlyForecast(
                        dateTime = now,
                        temperature = 70f,
                        condition = "Clear",
                        source = WeatherSource.WEATHER_API.id,
                    ),
                ),
            daily =
                listOf(
                    DailyForecast(
                        date = today.toString(),
                        highTemp = 75f,
                        lowTemp = 56f,
                        condition = "Clear",
                    ),
                ),
        )

    private fun historyReadings(count: Int): List<ObservationReading> {
        val start = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        return (0 until count).map { index ->
            ObservationReading(
                stationId = "WEATHER_API_MAIN",
                stationName = "WeatherAPI: History Backfill",
                timestamp = start + index * 3_600_000L,
                temperature = 55f + index,
                condition = "Clear",
                locationLat = lat,
                locationLon = lon,
                distanceKm = 0f,
                stationType = "OFFICIAL",
                api = WeatherSource.WEATHER_API.id,
                precipAmountMm = if (index == 4) 1.2f else 0f,
            )
        }
    }
}
