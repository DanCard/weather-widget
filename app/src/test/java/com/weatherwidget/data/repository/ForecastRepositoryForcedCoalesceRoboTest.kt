package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The forced-request time limit lives under `syncMutex` (2026-09-12: a forced `hourly_gaps` sync
 * queued behind the location-move fetch and re-fetched all five sources 70 ms after it finished —
 * performance/260912-forced-sync-refetches-under-the-lock.md).
 *
 * Every pre-lock early exit in `getWeatherData` is `!forceRefresh`, so a forced call can only
 * return without `NET_FETCH_START` through the coalesce branch. That makes these sequential calls
 * a faithful stand-in for the concurrent case: the first call's completed fetch is exactly what
 * the second caller finds once the first releases the lock.
 *
 * The APIs are relaxed mocks, so "a fetch" here is `NET_FETCH_START` → `NET_FETCH_COMPLETE` with
 * no rows — which is the point: satisfaction is keyed on the completed fetch, not on row stamps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ForecastRepositoryForcedCoalesceRoboTest {

    private val lat = 37.372
    private val lon = -121.981

    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        every { stateManager.isSourceVisible(any()) } returns true
        repository = ForecastRepository(
            context = RuntimeEnvironment.getApplication(),
            forecastDao = db.forecastDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            nwsApi = mockk(relaxed = true),
            openMeteoApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = stateManager,
            climateNormalDao = db.climateNormalDao(),
            observationDao = db.observationDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
            observationRepository = mockk(relaxed = true),
            tomorrowIoApi = mockk(relaxed = true),
            openWeatherMapApi = mockk(relaxed = true),
            nwsForecastMapper = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = db.close()

    /** The location move's own force: unstamped, non-unique enqueue. It fetches and records the fetch. */
    private suspend fun priorFetch(): Long {
        repository.getWeatherData(lat, lon, forceRefresh = true)
        assertEquals("fixture: the first forced call must fetch", 1, starts())
        val completes = db.appLogDao().getLogsByTag("NET_FETCH_COMPLETE", 10)
        assertEquals("fixture: the first fetch must complete", 1, completes.size)
        return completes.single().timestamp
    }

    /** hourly_gaps: requested 5 s before the in-flight fetch completed → satisfied, no second fetch. */
    @Test
    fun `a forced request older than the completed fetch is coalesced under the lock`() = runBlocking {
        val completedAt = priorFetch()

        val result = repository.getWeatherData(lat, lon, forceRefresh = true, requestedAtMs = completedAt - 5_000)

        assertTrue(result.isSuccess)
        assertEquals(1, starts())
        assertEquals(1, db.appLogDao().getLogsByTag("NET_FETCH_COALESCED", 10).size)
    }

    /** Control: a request made AFTER the fetch completed still fetches — proves the branch can fail. */
    @Test
    fun `a forced request newer than the completed fetch still fetches`() = runBlocking {
        val completedAt = priorFetch()

        repository.getWeatherData(lat, lon, forceRefresh = true, requestedAtMs = completedAt + 1_000)

        assertEquals(2, starts())
        assertEquals(0, db.appLogDao().getLogsByTag("NET_FETCH_COALESCED", 10).size)
    }

    /** Enqueue paths that do not stamp a request time keep their force. */
    @Test
    fun `an unstamped forced request still fetches`() = runBlocking {
        priorFetch()

        repository.getWeatherData(lat, lon, forceRefresh = true)

        assertEquals(2, starts())
    }

    /** The Mountain View fetch completing must not satisfy a Santa Clara request. */
    @Test
    fun `a fetch for a different site does not coalesce`() = runBlocking {
        val completedAt = priorFetch()

        repository.getWeatherData(37.417, -122.089, forceRefresh = true, requestedAtMs = completedAt - 5_000)

        assertEquals(2, starts())
    }

    /** A toggle to a source the untargeted fetch did not cover still fetches it. */
    @Test
    fun `a targeted request for an unfetched source does not coalesce`() = runBlocking {
        val completedAt = priorFetch()

        repository.getWeatherData(
            lat, lon, forceRefresh = true, targetSourceId = WeatherSource.SILURIAN.id, requestedAtMs = completedAt - 5_000,
        )

        assertEquals(2, starts())
    }

    private suspend fun starts() = db.appLogDao().getLogsByTag("NET_FETCH_START", 10).size
}
