package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

@Category(ShortDuration::class)
class WeatherApiHistoryBackfillerTest {
    private val lat = 37.417
    private val lon = -122.089
    private val zone = ZoneId.of("America/Los_Angeles")
    private val yesterday = LocalDate.of(2026, 7, 27)
    private val now =
        LocalDate.of(2026, 7, 28).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val weatherApi = mockk<WeatherApi>()
    private val observationDao = mockk<ObservationDao>()
    private val hourlyStore = mockk<HourlyForecastStore>(relaxed = true)
    private val observationRepository = mockk<ObservationRepository>(relaxed = true)
    private val appLogDao = mockk<AppLogDao>(relaxed = true)

    private fun subject() =
        WeatherApiHistoryBackfiller(
            weatherApi = weatherApi,
            observationDao = observationDao,
            hourlyStore = hourlyStore,
            observationRepository = observationRepository,
            appLogDao = appLogDao,
            nowProvider = { now },
            zoneIdProvider = { zone },
        )

    @Test
    fun `missing yesterday fetches stores and recomputes exactly once`() = runTest {
        coEvery { observationDao.getObservationsInRange(any(), any(), lat, lon) } returns emptyList()
        coEvery {
            appLogDao.getLatestLogByTagAndMessagePrefix(any(), any())
        } returns null
        val hours = historyHours(24)
        coEvery { weatherApi.getHistory(lat, lon, yesterday) } returns
            ForecastResult(hourly = hours)

        val result = subject().backfillIfNeeded(lat, lon)

        assertEquals(WeatherApiHistoryBackfillStatus.FETCHED, result.status)
        assertEquals(24, result.storedHours)
        coVerify(exactly = 1) {
            hourlyStore.saveHourlyEntitiesFromShared(
                hours,
                lat,
                lon,
                WeatherSource.WEATHER_API.id,
            )
        }
        coVerify(exactly = 1) {
            observationRepository.recomputeDailyExtremesFromStoredObservations(
                lat,
                lon,
                yesterday,
                yesterday,
                emptyList(),
            )
        }
    }

    @Test
    fun `complete yesterday performs no history call`() = runTest {
        coEvery { observationDao.getObservationsInRange(any(), any(), lat, lon) } returns
            historyHours(20).map {
                ObservationEntity(
                    stationId = "WEATHER_API_MAIN",
                    stationName = "WeatherAPI history",
                    timestamp = it.dateTime,
                    temperature = it.temperature,
                    condition = it.condition,
                    locationLat = lat,
                    locationLon = lon,
                    api = WeatherSource.WEATHER_API.id,
                )
            }
        coEvery {
            appLogDao.getLatestLogByTagAndMessagePrefix(any(), any())
        } returns null

        val result = subject().backfillIfNeeded(lat, lon)

        assertEquals(WeatherApiHistoryBackfillStatus.ALREADY_COVERED, result.status)
        coVerify(exactly = 0) { weatherApi.getHistory(any(), any(), any()) }
    }

    @Test
    fun `history access failure is optional and records cooldown`() = runTest {
        coEvery { observationDao.getObservationsInRange(any(), any(), lat, lon) } returns emptyList()
        coEvery {
            appLogDao.getLatestLogByTagAndMessagePrefix(any(), any())
        } returns null
        coEvery { weatherApi.getHistory(lat, lon, yesterday) } throws
            ApiAccessException(
                source = WeatherSource.WEATHER_API,
                statusCode = 403,
                detail = "forbidden",
                message = "forbidden",
            )

        val result = subject().backfillIfNeeded(lat, lon)

        assertEquals(WeatherApiHistoryBackfillStatus.FAILED, result.status)
        assertTrue(result.retryAtMs!! > now)
        coVerify(exactly = 0) {
            hourlyStore.saveHourlyEntitiesFromShared(any(), any(), any(), any())
        }
    }

    @Test(expected = CancellationException::class)
    fun `history cancellation propagates`() = runTest {
        coEvery { observationDao.getObservationsInRange(any(), any(), lat, lon) } returns emptyList()
        coEvery {
            appLogDao.getLatestLogByTagAndMessagePrefix(any(), any())
        } returns null
        coEvery { weatherApi.getHistory(lat, lon, yesterday) } throws CancellationException()

        subject().backfillIfNeeded(lat, lon)
    }

    private fun historyHours(count: Int): List<HourlyForecast> {
        val start = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        return (0 until count).map { index ->
            HourlyForecast(
                dateTime = start + index * 3_600_000L,
                temperature = 55f + index,
                condition = "Clear",
                precipAmountMm = index / 10f,
            )
        }
    }
}
