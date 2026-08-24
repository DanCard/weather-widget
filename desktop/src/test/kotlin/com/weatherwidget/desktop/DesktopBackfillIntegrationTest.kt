package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toEntity
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import org.junit.experimental.categories.Category

/**
 * GENERIC_GAP ('Generic') is a FUTURE-ONLY forecast filler — it covers forecast hours beyond the
 * selected API's horizon and must NEVER stand in for history (its Open-Meteo decimals would
 * masquerade as the real, whole-degree NWS forecast, or invent a forecast for hours we never made).
 *
 * These tests pin that contract: refresh() does not backfill Open-Meteo into the past, and
 * getHourlyHistory() excludes past Generic rows while still admitting future ones. (This class used
 * to verify a one-time Open-Meteo history backfill — that behaviour was removed precisely because it
 * violated the future-only rule.)
 */
@Category(MediumDuration::class)
class DesktopBackfillIntegrationTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val weatherService = mockk<WeatherApiClient>()

    private val lat = 37.4220
    private val lon = -122.0841
    private val source = "NWS"

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-backfill-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `refresh never backfills Open-Meteo into history`() = runTest {
        val baseHour = (System.currentTimeMillis() / 3600_000L) * 3600_000L
        coEvery { weatherService.fetchForecast() } returns RawFetch(
            providerCurrentTemp = 70f,
            hourly = listOf(HourlyForecast(baseHour, 70f, "Clear", source = "NWS")),
            daily = emptyList(),
        )

        repository.refresh()

        // History must come only from real accumulated snapshots — never an Open-Meteo past_days pull.
        coVerify(exactly = 0) { weatherService.fetchHistory(any()) }
    }

    @Test
    fun `Open-Meteo provider current does not update the header`() = runTest {
        val baseHour = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
        val now = baseHour + 30 * 60_000L
        val meteoRepository = DesktopWeatherRepository(
            weatherService,
            dao,
            lat,
            lon,
            WeatherSource.OPEN_METEO.id,
            currentTimeMillis = { now },
        )
        coEvery { weatherService.fetchForecast() } returns RawFetch(
            providerCurrentTemp = 99f,
            hourly = listOf(
                HourlyForecast(baseHour, 60f, "Clear", source = WeatherSource.OPEN_METEO.id),
                HourlyForecast(baseHour + 3_600_000L, 70f, "Clear", source = WeatherSource.OPEN_METEO.id),
            ),
        )
        coEvery { weatherService.fetchObservationsOnly(recentOnly = false) } returns RawFetch()

        val result = meteoRepository.refresh(now)

        assertEquals(65f, result.resolved.currentTemp!!, 0.01f)
    }

    @Test
    fun `Open-Meteo full refresh persists bounded borrowed METAR recovery`() = runTest {
        val now = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
        val recoveryTime = now - 6 * 3_600_000L
        val meteoRepository = DesktopWeatherRepository(
            weatherService,
            dao,
            lat,
            lon,
            WeatherSource.OPEN_METEO.id,
            currentTimeMillis = { now },
        )
        coEvery { weatherService.fetchForecast() } returns RawFetch(
            hourly = listOf(
                HourlyForecast(now, 70f, "Clear", source = WeatherSource.OPEN_METEO.id),
            ),
        )
        coEvery { weatherService.fetchObservationsOnly(recentOnly = false) } returns RawFetch(
            rawObservations = listOf(
                ObservationReading(
                    stationId = "KNUQ",
                    stationName = "Moffett Federal Airfield",
                    timestamp = recoveryTime,
                    temperature = 61f,
                    condition = "Overcast",
                    locationLat = lat,
                    locationLon = lon,
                    distanceKm = 4f,
                    api = WeatherSource.METAR.id,
                    cloudCoverLow = 100,
                    isMetar = true,
                ),
            ),
        )

        meteoRepository.refresh(now)

        coVerify(exactly = 1) { weatherService.fetchObservationsOnly(recentOnly = false) }
        val stored = dao.getObservationsInRange(
            recoveryTime - 1,
            recoveryTime + 1,
            lat,
            lon,
        )
        assertEquals(1, stored.size)
        assertEquals(WeatherSource.METAR.id, stored.single().api)
        assertEquals(100, stored.single().cloudCoverLow)
    }

    @Test
    fun `failed borrowed METAR recovery preserves cached observations`() = runTest {
        val now = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
        val cachedTime = now - 6 * 3_600_000L
        dao.upsertObservations(
            listOf(
                ObservationReading(
                    stationId = "KNUQ",
                    stationName = "Moffett Federal Airfield",
                    timestamp = cachedTime,
                    temperature = 61f,
                    condition = "Overcast",
                    locationLat = lat,
                    locationLon = lon,
                    distanceKm = 4f,
                    api = WeatherSource.METAR.id,
                    cloudCoverLow = 100,
                    isMetar = true,
                ).toEntity(now),
            ),
        )
        val meteoRepository = DesktopWeatherRepository(
            weatherService,
            dao,
            lat,
            lon,
            WeatherSource.OPEN_METEO.id,
            currentTimeMillis = { now },
        )
        coEvery { weatherService.fetchForecast() } returns RawFetch(
            hourly = listOf(
                HourlyForecast(now, 70f, "Clear", source = WeatherSource.OPEN_METEO.id),
            ),
        )
        coEvery { weatherService.fetchObservationsOnly(recentOnly = false) } throws
            IllegalStateException("upstream unavailable")

        meteoRepository.refresh(now)

        val stored = dao.getObservationsInRange(cachedTime - 1, cachedTime + 1, lat, lon)
        assertEquals(1, stored.size)
        assertEquals("KNUQ", stored.single().stationId)
    }

    @Test
    fun `Tomorrow refresh does not rewrite elapsed forecast storage with recent history`() = runTest {
        val now = (System.currentTimeMillis() / 60_000L) * 60_000L
        val oldTimelineHour = now - 2 * 3_600_000L
        val currentTimelineHour = now - 30 * 60_000L
        val futureHour = now + 3_600_000L
        val tomorrowRepository = DesktopWeatherRepository(
            weatherService,
            dao,
            lat,
            lon,
            WeatherSource.TOMORROW_IO.id,
            currentTimeMillis = { now },
        )
        coEvery { weatherService.fetchForecast() } returns RawFetch(
            hourly = listOf(
                HourlyForecast(oldTimelineHour, 60f, "Past analysis", source = WeatherSource.TOMORROW_IO.id),
                HourlyForecast(currentTimelineHour, 64f, "Current", source = WeatherSource.TOMORROW_IO.id),
                HourlyForecast(futureHour, 68f, "Forecast", source = WeatherSource.TOMORROW_IO.id),
            ),
        )

        tomorrowRepository.refresh(now)

        val live = dao.getHourlyForecasts(
            lat,
            lon,
            WeatherSource.TOMORROW_IO.id,
            oldTimelineHour - 1L,
            futureHour + 1L,
        )
        assertEquals(listOf(currentTimelineHour, futureHour), live.map { it.dateTime })
        val archived = dao.getHourlyHistory(
            lat,
            lon,
            WeatherSource.TOMORROW_IO.id,
            oldTimelineHour - 1L,
            futureHour + 1L,
            nowMs = now,
        )
        assertEquals(false, archived.any { it.dateTime == oldTimelineHour })
    }

    @Test
    fun `getHourlyHistory excludes past Generic rows`() {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L
        val pastHour = baseHour - 48 * 3600_000L

        // A real NWS snapshot and an Open-Meteo Generic row for the SAME past hour.
        dao.upsertHourlyForecastHistory(
            lat, lon, "NWS", now - 4 * 3600_000L,
            listOf(HourlyForecast(pastHour, 60f, "Clear", source = "NWS")),
        )
        dao.upsertHourlyForecastHistory(
            lat, lon, WeatherSource.GENERIC_GAP.id, 0L,
            listOf(HourlyForecast(pastHour, 61.6f, "Cloudy", cloudCover = 80, source = WeatherSource.GENERIC_GAP.id)),
        )

        val rows = dao.getHourlyHistory(lat, lon, "NWS", pastHour - 3600_000L, pastHour + 3600_000L, nowMs = now)

        // Only the NWS row survives; the past Generic row (Open-Meteo decimals) is excluded.
        assertEquals(1, rows.size)
        assertEquals("NWS", rows[0].source)
        assertEquals(60f, rows[0].temperature)
    }

    @Test
    fun `getHourlyHistory includes future Generic rows`() {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L
        val futureHour = baseHour + 48 * 3600_000L

        dao.upsertHourlyForecastHistory(
            lat, lon, WeatherSource.GENERIC_GAP.id, 0L,
            listOf(HourlyForecast(futureHour, 75.4f, "Clear", source = WeatherSource.GENERIC_GAP.id)),
        )

        val rows = dao.getHourlyHistory(lat, lon, "NWS", futureHour - 3600_000L, futureHour + 3600_000L, nowMs = now)

        // Future Generic legitimately fills beyond the API horizon, so it IS admitted.
        assertEquals(1, rows.size)
        assertEquals(WeatherSource.GENERIC_GAP.id, rows[0].source)
    }
}
