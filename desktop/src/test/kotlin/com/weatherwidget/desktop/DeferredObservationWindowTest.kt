package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The wake catch-up publishes before it fills history: `refreshWithOutcome(deferObservationWindow
 * = true)` asks NWS for the recent observation window only, and
 * [DesktopWeatherRepository.refreshObservationWindow] then runs the shared NwsGapFillRun daily pass —
 * a 3-day pull only for days that are missing or incomplete. Real DB, mocked network seam.
 * performance/261004-desktop-wake-refresh-stalls-on-7day-obs-window.md,
 * plans/261025-shared-nws-gap-fill.md.
 */
@Category(ShortDuration::class)
class DeferredObservationWindowTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val weatherService = mockk<WeatherApiClient>(relaxed = true)

    private val lat = 37.4220
    private val lon = -122.0841
    private val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-deferred-window-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun repo(source: String = "NWS") =
        DesktopWeatherRepository(weatherService, dao, lat, lon, source, currentTimeMillis = { now })

    private fun reading(station: String, ts: Long, temp: Float) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = ts,
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        api = "NWS",
    )

    // NWS forecasts carry no observations (F6); the deferral shows in the observation pull's window.
    private fun stubForecast() {
        coEvery { weatherService.fetchForecast() } returns RawFetch(hourly = listOf(HourlyForecast(now, 72f, "Clear")))
        coEvery { weatherService.fetchObservationsOnly(any(), any()) } returns RawFetch(
            rawObservations = listOf(reading("KNUQ", now - 10 * 60_000L, 71f)),
        )
    }

    @Test
    fun `deferred refresh publishes from the recent window without the history fill`() = runTest {
        stubForecast()

        val outcome = repo().refreshWithOutcome(now, deferObservationWindow = true)

        assertEquals(1, outcome.snapshot.raw.hourly.size)
        coVerify(exactly = 1) { weatherService.fetchObservationsOnly(true, any()) }
        coVerify(exactly = 0) { weatherService.fetchObservationsOnly(false, any()) }
        coVerify(exactly = 0) { weatherService.fetchNwsStationWindow(any(), any(), any(), any(), any()) }
        val refreshRow = dao.getLatestLogByTagAndMessagePrefix("REFRESH", "source=NWS")
        assertNotNull(refreshRow)
        assertTrue(refreshRow!!.message, refreshRow.message.endsWith("obsWindow=deferred"))
    }

    @Test
    fun `default refresh is unchanged - full window, no deferral marker`() = runTest {
        stubForecast()

        repo().refreshWithOutcome(now)

        coVerify(exactly = 1) { weatherService.fetchObservationsOnly(false, any()) }
        val refreshRow = dao.getLatestLogByTagAndMessagePrefix("REFRESH", "source=NWS")!!
        assertTrue(refreshRow.message, !refreshRow.message.contains("obsWindow"))
    }

    // --- The deferred window is the shared NwsGapFillRun daily pass (plans/261025-shared-nws-gap-fill.md) ---

    private val knuq = com.weatherwidget.data.remote.NwsApi.StationInfo("KNUQ", "KNUQ", lat, lon)

    /** Hourly KNUQ readings over the last 72 h: every past day reaches the afternoon. */
    private val threeDays = (1..72).map { h -> reading("KNUQ", now - h * 3600_000L, 60f + h % 10) }

    private fun stubGapFill(rows: List<ObservationReading> = threeDays) {
        coEvery { weatherService.nwsObservationStations() } returns
            com.weatherwidget.data.remote.FetchOutcome.Success(listOf(knuq))
        coEvery { weatherService.fetchNwsStationWindow(any(), any(), any(), any(), any()) } returns
            com.weatherwidget.shared.observations.NwsGapFillRun.StationRows(rows, usedWebFallback = false)
    }

    @Test
    fun `observation window fills the missing days with a 3-day pull, not 7`() = runTest {
        stubGapFill()

        val stored = repo().refreshObservationWindow(listOf(com.weatherwidget.data.model.WeatherSource.NWS), "test", now)

        assertEquals(72, stored)
        val start = java.time.Instant.ofEpochMilli(now).minus(java.time.Duration.ofDays(3)).toString()
        coVerify(exactly = 1) { weatherService.fetchNwsStationWindow(knuq, 0, start, any(), "NWS_DAILY_WEB_FALLBACK") }
        coVerify(exactly = 0) { weatherService.fetchObservationHistory(any()) }
        val inDb = dao.getObservationsInRange(now - 73 * 3600_000L, now, lat, lon).count { it.stationId == "KNUQ" }
        assertEquals(72, inDb)
        val row = dao.getLatestLogByTagAndMessagePrefix("NWS_DAILY_BACKFILL_RESULT", "reason=test need=[")
        assertNotNull(row)
        assertTrue(row!!.message, "rows=72" in row.message && "remaining=[]" in row.message)
    }

    @Test
    fun `a wake with every day complete makes no request`() = runTest {
        stubGapFill()
        repo().refreshObservationWindow(listOf(com.weatherwidget.data.model.WeatherSource.NWS), "test", now)

        assertEquals(0, repo().refreshObservationWindow(listOf(com.weatherwidget.data.model.WeatherSource.NWS), "test", now))

        coVerify(exactly = 1) { weatherService.fetchNwsStationWindow(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { weatherService.nwsObservationStations() }
        assertNotNull(dao.getLatestLogByTagAndMessagePrefix("NWS_DAILY_BACKFILL_RESULT", "reason=test need=none rows=0"))
    }

    @Test
    fun `NWS unreachable is logged and swallowed - the forecast is already out`() = runTest {
        coEvery { weatherService.nwsObservationStations() } returns
            com.weatherwidget.data.remote.FetchOutcome.Failed("HttpRequestTimeoutException: timeout")

        assertEquals(-1, repo().refreshObservationWindow(listOf(com.weatherwidget.data.model.WeatherSource.NWS), "test", now))

        val row = dao.getLatestLogByTagAndMessagePrefix("NWS_DAILY_BACKFILL_RESULT", "reason=test need=")
        assertNotNull(row)
        assertEquals("WARN", row!!.level)
        assertTrue(row.message, "unreachable=true" in row.message)
    }

    @Test
    fun `a station window that throws is logged per station and reads as unreachable`() = runTest {
        coEvery { weatherService.nwsObservationStations() } returns
            com.weatherwidget.data.remote.FetchOutcome.Success(listOf(knuq))
        coEvery { weatherService.fetchNwsStationWindow(any(), any(), any(), any(), any()) } throws
            EOFException("Invalid chunk: content block of size 49152 ended unexpectedly")

        assertEquals(-1, repo().refreshObservationWindow(listOf(com.weatherwidget.data.model.WeatherSource.NWS), "test", now))

        assertNotNull(dao.getLatestLogByTagAndMessagePrefix("NWS_DAILY_BACKFILL_STATION_FAIL", "station=KNUQ"))
    }

    @Test
    fun `non-NWS sources have no deferred window and make no request`() = runTest {
        val served = listOf(com.weatherwidget.data.model.WeatherSource.OPEN_METEO)
        assertEquals(0, repo(source = "OPEN_METEO").refreshObservationWindow(served, "test", now))
        coVerify(exactly = 0) { weatherService.nwsObservationStations() }
        coVerify(exactly = 0) { weatherService.fetchNwsStationWindow(any(), any(), any(), any(), any()) }
    }

    /** Android's sync fills for any visible NWS reader; desktop used to gate on the displayed source alone. */
    @Test
    fun `a visible NWS source is filled while another source is displayed`() = runTest {
        stubGapFill()
        val served = listOf(com.weatherwidget.data.model.WeatherSource.OPEN_METEO, com.weatherwidget.data.model.WeatherSource.NWS)

        assertEquals(72, repo(source = "OPEN_METEO").refreshObservationWindow(served, "test", now))
    }

    // --- Borrowed NWS (plans/261007-desktop-borrowed-nws-actuals-not-fetched-on-full-refresh.md) ---

    private fun withGoogleBorrowingNws(block: suspend () -> Unit) = runTest {
        com.weatherwidget.shared.observations.ActualsProviderResolver.installPreferenceSource {
            if (it == com.weatherwidget.data.model.WeatherSource.GOOGLE_WEATHER) com.weatherwidget.data.model.WeatherSource.NWS else null
        }
        try {
            block()
        } finally {
            com.weatherwidget.shared.observations.ActualsProviderResolver.resetPreferenceSource()
        }
    }

    @Test
    fun `google borrowing NWS - refresh fetches NWS observations, reusing fresh stored rows`() = withGoogleBorrowingNws {
        dao.upsertObservations(
            listOf(
                com.weatherwidget.data.local.desktop.DesktopObservationEntity(
                    stationId = "KNUQ", stationName = "KNUQ", timestamp = now - 35 * 60_000L,
                    temperature = 66.2f, condition = "Clear", locationLat = lat, locationLon = lon,
                    fetchedAt = now - 30 * 60_000L, api = "NWS",
                ),
            ),
        )
        coEvery { weatherService.fetchForecast() } returns RawFetch(hourly = listOf(HourlyForecast(now, 64f, "Clear")))
        coEvery { weatherService.fetchObservationsOnly(true, any()) } returns RawFetch(
            rawObservations = listOf(reading("KNUQ", now - 15 * 60_000L, 64.4f)),
        )

        val outcome = repo(source = "GOOGLE_WEATHER").refreshWithOutcome(now)

        assertTrue("the refresh supplied NWS observations", outcome.suppliedObservations)
        coVerify(exactly = 1) { weatherService.fetchObservationsOnly(true, any()) }
        coVerify(exactly = 0) { weatherService.fetchObservationsOnly(false, any()) }
        val newest = dao.getNewestObservationTimestampForApi("NWS", lat, lon)
        assertEquals(now - 15 * 60_000L, newest)
        assertNotNull(dao.getLatestLogByTagAndMessagePrefix("BORROWED_NWS_RECOVERY", "source=GOOGLE_WEATHER recentOnly=true rows=1"))
    }

    @Test
    fun `google borrowing NWS - no stored rows takes the full pull`() = withGoogleBorrowingNws {
        coEvery { weatherService.fetchForecast() } returns RawFetch(hourly = listOf(HourlyForecast(now, 64f, "Clear")))
        coEvery { weatherService.fetchObservationsOnly(false, any()) } returns RawFetch()

        repo(source = "GOOGLE_WEATHER").refreshWithOutcome(now)

        coVerify(exactly = 1) { weatherService.fetchObservationsOnly(false, any()) }
    }

    @Test
    fun `google borrowing NWS - deferred window applies`() = withGoogleBorrowingNws {
        stubGapFill()

        val served = listOf(com.weatherwidget.data.model.WeatherSource.GOOGLE_WEATHER)
        assertEquals(72, repo(source = "GOOGLE_WEATHER").refreshObservationWindow(served, "test", now))
    }
}
