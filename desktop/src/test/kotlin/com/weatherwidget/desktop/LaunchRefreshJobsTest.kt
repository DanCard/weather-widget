package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * A source change leaves the launch refresh already in flight running: [LaunchRefreshJobs] with a real
 * [DesktopWeatherRepository] and DB, the service held mid-fetch until the test releases it.
 * plans/261012-desktop-source-change-keeps-in-flight-fetch.md
 */
@Category(ShortDuration::class)
class LaunchRefreshJobsTest {
    private val tempDbPath = Files.createTempFile("weather-launch-refresh-jobs-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lat = 37.4220
    private val lon = -122.0841
    private val source = WeatherSource.OPEN_METEO.id

    private val raw = RawFetch(
        daily = listOf(DailyForecast(LocalDate.now().plusDays(1).toString(), 70f, 50f, "Cloudy")),
        hourly = (1..5).map { HourlyForecast(dateTime = (System.currentTimeMillis() / 3_600_000L + it) * 3_600_000L, temperature = 60f, condition = "Cloudy") },
    )

    /** A service whose forecast fetch waits for [release]; [entered] completes once it is waiting. */
    private inner class HeldService {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = mockk<WeatherApiClient>(relaxed = true).also { svc ->
            suspend fun held(): RawFetch {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                return raw
            }
            coEvery { svc.fetchForecast(any()) } coAnswers { held() }
            coEvery { svc.fetchForecast(any(), any()) } coAnswers { held() }
            coEvery { svc.fetchForecast(any(), any(), any()) } coAnswers { held() }
        }
        val repo = DesktopWeatherRepository(client, dao, lat, lon, source)
    }

    private val key = "$source@$lat,$lon"

    @After
    fun teardown() {
        scope.cancel()
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun cancelledLogged() = dao.getRecentLogsByTags(listOf("REFRESH_CANCELLED")).isNotEmpty()

    @Test
    fun `only a source change leaves the previous launch refresh running`() {
        assertTrue(launchRefreshSurvivesRestart(SOURCE_CYCLE_REASON))
        listOf(STARTUP_REASON, "source_or_location_change", "actuals_provider_change", "resume:logind", "network:restored")
            .forEach { assertFalse(it, launchRefreshSurvivesRestart(it)) }
    }

    @Test
    fun `a launch refresh paints only while its source and site are displayed`() {
        val nws = DesktopConfig(lat, lon, "Home", settings = DesktopSettings(weatherSource = WeatherSource.NWS.id))
        val om = nws.copy(settings = nws.settings.copy(weatherSource = WeatherSource.OPEN_METEO.id))
        assertTrue(launchRefreshPublishes(nws, nws))
        assertFalse(launchRefreshPublishes(nws, om))
        assertFalse(launchRefreshPublishes(nws, nws.copy(lat = lat + 0.1)))
        assertFalse(launchRefreshPublishes(nws, null))
    }

    /** 2026-10-10 07:08:40: NWS cancelled 1 s in by clicking on to Google. */
    @Test
    fun `a source change does not cancel the fetch in flight, which stores its rows`() = runBlocking {
        val held = HeldService()
        val jobs = LaunchRefreshJobs(scope)
        jobs.start(STARTUP_REASON) { jobs.fetchOnce(key) { held.repo.refresh(reason = "launch:startup") } }
        withTimeout(10_000) { held.entered.await() }

        val first = jobs.start(SOURCE_CYCLE_REASON) {}
        held.release.complete(Unit)
        withTimeout(10_000) { first!!.join() }

        assertFalse(first!!.isCancelled)
        assertNotNull("the NWS-equivalent refresh finished and logged REFRESH", dao.getLastSuccessfulFetch(source))
        assertFalse(cancelledLogged())
    }

    @Test
    fun `a location change still cancels the fetch in flight`() = runBlocking {
        val held = HeldService()
        val jobs = LaunchRefreshJobs(scope)
        jobs.start(STARTUP_REASON) { jobs.fetchOnce(key) { held.repo.refresh(reason = "launch:startup") } }
        withTimeout(10_000) { held.entered.await() }

        val first = jobs.start("source_or_location_change") {}
        withTimeout(10_000) { first!!.join() }

        assertTrue(first!!.isCancelled)
        assertNull(dao.getLastSuccessfulFetch(source))
        assertTrue(cancelledLogged())
    }

    @Test
    fun `cycling back to a source whose fetch is in flight does not ask it twice`() = runBlocking {
        val held = HeldService()
        val jobs = LaunchRefreshJobs(scope)
        jobs.start(STARTUP_REASON) { jobs.fetchOnce(key) { held.repo.refresh(reason = "launch:startup") } }
        withTimeout(10_000) { held.entered.await() }
        // start() returns the PREVIOUS launch refresh: here the startup one, still held mid-fetch.
        val startupRefresh = jobs.start(SOURCE_CYCLE_REASON) { jobs.fetchOnce("GOOGLE_WEATHER@$lat,$lon") { Unit } }

        // Await the new refresh's own answer. Joining the job start() returned waited on the
        // previous (Google) one, and the skip check — on Dispatchers.Default — had sometimes not run
        // yet: "expected:<true> but was:<null>" (flaky 2026-10-12/13).
        val skipped = CompletableDeferred<Boolean>()
        jobs.start(SOURCE_CYCLE_REASON) { skipped.complete(jobs.fetchOnce(key) { held.repo.refresh(reason = "launch:again") } == null) }
        assertTrue("the second ask for the same source is skipped", withTimeout(10_000) { skipped.await() })

        held.release.complete(Unit)
        withTimeout(10_000) { startupRefresh!!.join() }
        assertEquals("one service request for the source", 1, held.calls.get())
        assertNotNull(dao.getLastSuccessfulFetch(source))
    }

    @Test
    fun `a fetch whose owner was cancelled does not block the next one for the same key`() = runBlocking {
        val held = HeldService()
        val jobs = LaunchRefreshJobs(scope)
        jobs.start(STARTUP_REASON) { jobs.fetchOnce(key) { held.repo.refresh(reason = "launch:startup") } }
        withTimeout(10_000) { held.entered.await() }

        // Resume restart: cancels the first; the new one for the same key must fetch, not skip.
        val fetched = CompletableDeferred<Boolean>()
        jobs.start("resume:logind") { fetched.complete(jobs.fetchOnce(key) { Unit } != null) }
        assertTrue(withTimeout(10_000) { fetched.await() })
    }

    @Test
    fun `the replaced client closes after the grace and only once the detached refresh is done`() = runTest {
        val jobs = LaunchRefreshJobs(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val previous = backgroundScope.launch { release.await() }
        var closed = false
        jobs.closeWhenDone(previous, REPLACED_CLIENT_CLOSE_GRACE_MS) { closed = true }

        advanceTimeBy(REPLACED_CLIENT_CLOSE_GRACE_MS * 3)
        runCurrent()
        assertFalse("still in use by the detached refresh", closed)

        release.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertTrue(closed)
    }

    @Test
    fun `with no detached refresh the replaced client closes after the grace`() = runTest {
        val jobs = LaunchRefreshJobs(backgroundScope)
        var closed = false
        jobs.closeWhenDone(null, REPLACED_CLIENT_CLOSE_GRACE_MS) { closed = true }
        advanceTimeBy(REPLACED_CLIENT_CLOSE_GRACE_MS - 1)
        runCurrent()
        assertFalse(closed)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(closed)
    }
}
