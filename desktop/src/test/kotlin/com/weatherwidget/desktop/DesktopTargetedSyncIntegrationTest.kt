package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.shared.util.TargetedForecastSyncRun.Enqueue
import com.weatherwidget.shared.util.TargetedForecastSyncRun.TailState
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop half of plans/261123-shared-targeted-sync-paint-then-tail.md: real SQLite, the real
 * [DesktopWeatherRepository], `:shared` [TargetedForecastSyncRun] through [DesktopTargetedSyncHost], and
 * the [DesktopPostSyncTail] timer. A viewing or switched-to fetch publishes before any actuals recompute;
 * the recompute lands in the tail, which republishes only when the stored actuals changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Category(ShortDuration::class)
class DesktopTargetedSyncIntegrationTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val weatherService = mockk<WeatherApiClient>(relaxed = true)

    private val lat = 37.4220
    private val lon = -122.0841
    private val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-targeted-sync-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        // Today's KNUQ readings come with the forecast, so a recompute has a day to reduce.
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val readings = (0 until ((now - midnight) / 3600_000L).toInt().coerceAtLeast(1)).map { h ->
            reading("KNUQ", midnight + h * 3600_000L, 55f + h)
        }
        coEvery { weatherService.fetchForecast() } returns RawFetch(hourly = listOf(HourlyForecast(now, 72f, "Clear")))
        coEvery { weatherService.fetchObservationsOnly(any(), any()) } returns RawFetch(rawObservations = readings)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
        Files.deleteIfExists(DesktopMetarReconcileBootStore.markerFor(tempDbPath))
    }

    private fun repo() = DesktopWeatherRepository(weatherService, dao, lat, lon, "NWS", currentTimeMillis = { now })

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

    @Test
    fun `a targeted fetch publishes before the recompute, and the tail recomputes and republishes`() = runTest {
        val repo = repo()
        val events = mutableListOf<String>()
        val tailScope = TestScope(StandardTestDispatcher(testScheduler))
        val tailRuns = mutableListOf<Set<String>>()
        val tail = DesktopPostSyncTail(tailScope) { ids -> tailRuns += ids }
        val run = TargetedForecastSyncRun { now }
        val host = DesktopTargetedSyncHost(
            repo = repo,
            weatherDao = dao,
            tail = tail,
            visibleSources = { setOf("NWS") },
            userSyncActive = { false },
            publish = { events += "republish" },
            paint = { events += "paint extremes=${repo.actualsSignature(now).size}" },
        )

        repo.refreshWithOutcome(now, reason = "viewing_forecast_stale", deferActuals = true)
        run.onFetched(host, "NWS", stored = true)
        println("fast path events=$events tailState=${tail.state()}")

        assertEquals("the paint saw no recomputed extremes", listOf("paint extremes=0"), events)
        assertEquals(TailState.PENDING, tail.state())
        val refreshRow = dao.getLatestLogByTagAndMessagePrefix("REFRESH", "source=NWS")!!
        assertTrue(refreshRow.message, "extremes=deferred" in refreshRow.message)

        tailScope.advanceTimeBy(TargetedForecastSyncRun.TAIL_DELAY_MS + 1)
        tailScope.runCurrent()
        assertEquals(listOf(setOf("NWS")), tailRuns)

        // The tail itself, as the daemon's timer runs it, a tail delay after the request.
        val tailRun = TargetedForecastSyncRun { now + TargetedForecastSyncRun.TAIL_DELAY_MS }
        val outcome = tailRun.runTail(host, setOf("NWS"))
        val extremes = repo.actualsSignature(now)
        println("tail outcome=$outcome events=$events extremes=${extremes.size}")
        assertEquals(TargetedForecastSyncRun.TailOutcome.RAN, outcome)
        assertTrue("the tail recomputed today's extremes", extremes.isNotEmpty())
        assertEquals("changed actuals republish once", "republish", events.last())
        val result = dao.getLatestLogByTagAndMessagePrefix(TargetedForecastSyncRun.TAG_RESULT, "changed=")!!
        assertTrue(result.message, "changed=true" in result.message)

        // A second tail with nothing new does not republish.
        events.clear()
        tailRun.runTail(host, setOf("NWS"))
        assertTrue("unchanged actuals: $events", events.none { it == "republish" })
    }

    @Test
    fun `a default refresh still recomputes inline`() = runTest {
        val repo = repo()
        repo.refreshWithOutcome(now, reason = "user_refresh:test")
        assertTrue(repo.actualsSignature(now).isNotEmpty())
        val refreshRow = dao.getLatestLogByTagAndMessagePrefix("REFRESH", "source=NWS")!!
        assertTrue(refreshRow.message, "extremes=deferred" !in refreshRow.message)
    }

    @Test
    fun `the tail timer keeps one pending tail, never cancels a running one, and runs one more after it`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val runs = mutableListOf<Set<String>>()
        val release = CompletableDeferred<Unit>()
        lateinit var tail: DesktopPostSyncTail
        tail = DesktopPostSyncTail(scope) { ids ->
            runs += ids
            if (runs.size == 1) release.await()
        }

        tail.schedule(Enqueue.SCHEDULE, 12_000L, setOf("NWS"))
        tail.schedule(Enqueue.KEEP_PENDING, 12_000L, setOf("OPEN_METEO"))
        tail.schedule(Enqueue.SCHEDULE, 12_000L, setOf("SILURIAN")) // a second SCHEDULE joins the pending one
        assertEquals(TailState.PENDING, tail.state())
        scope.advanceTimeBy(12_001L)
        scope.runCurrent()
        assertEquals(TailState.RUNNING, tail.state())
        assertEquals(listOf(setOf("NWS")), runs)

        tail.schedule(Enqueue.APPEND_AFTER_RUNNING, 5_000L, setOf("OPEN_METEO"))
        assertEquals("the running tail is not cancelled", TailState.RUNNING, tail.state())
        release.complete(Unit)
        scope.runCurrent()
        assertEquals(TailState.PENDING, tail.state())
        scope.advanceTimeBy(5_001L)
        scope.runCurrent()
        println("runs=$runs state=${tail.state()}")
        assertEquals(listOf(setOf("NWS"), setOf("OPEN_METEO")), runs)
        assertEquals(TailState.NONE, tail.state())
    }
}
