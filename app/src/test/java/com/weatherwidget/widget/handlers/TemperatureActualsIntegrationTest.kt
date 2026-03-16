package com.weatherwidget.widget.handlers

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.repository.CurrentTempRepository
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureActualsIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 42

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------
    // Zoom reset tests — pure WidgetStateManager, no network needed
    // -------------------------------------------------------------------

    // handleSetView also attempts a widget RemoteViews update which requires a real registered
    // widget. We absorb that NPE with runCatching — zoom state is persisted to SharedPreferences
    // before the update fires, so asserts are valid regardless.

    @Test
    fun `handleSetView from DAILY resets zoom to WIDE when entering TEMPERATURE`() = runTest {
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.TEMPERATURE)
        }

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(appWidgetId))
    }

    @Test
    fun `handleSetView from DAILY resets zoom to WIDE when entering PRECIPITATION`() = runTest {
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.PRECIPITATION)
        }

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(appWidgetId))
    }

    @Test
    fun `handleSetView from DAILY resets zoom to WIDE when entering CLOUD_COVER`() = runTest {
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.CLOUD_COVER)
        }

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(appWidgetId))
    }

    @Test
    fun `handleSetView preserves NARROW zoom when switching between hourly view types`() = runTest {
        // Start in TEMPERATURE at NARROW zoom
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setZoomLevel(appWidgetId, ZoomLevel.NARROW)

        // Switch to PRECIPITATION — should NOT reset zoom
        runCatching {
            WidgetIntentRouter.handleSetView(context, appWidgetId, ViewMode.PRECIPITATION)
        }

        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(appWidgetId))
    }

    // -------------------------------------------------------------------
    // Seed tests — backfillNwsObservationsIfNeeded with real DAOs, mocked network
    // -------------------------------------------------------------------

    @Test
    fun `seed converts existing observations to actuals on first call`() = runTest {
        val observationDao = db.observationDao()
        val hourlyActualDao = db.hourlyActualDao()

        // Insert 3 recent observations (within the last month window the seed uses)
        val recentTs = System.currentTimeMillis() - 3600_000L // 1 hour ago
        observationDao.insertAll(listOf(
            TestData.observation(timestamp = recentTs - 7200_000L, temperature = 60f),
            TestData.observation(timestamp = recentTs - 3600_000L, temperature = 62f),
            TestData.observation(timestamp = recentTs,              temperature = 64f),
        ))

        // Actuals table is empty — seed should fire
        val preCount = hourlyActualDao.getActualsInRange(
            "2000-01-01T00:00", "2099-12-31T23:00", "NWS", TestData.LAT, TestData.LON,
        )
        assertTrue("Actuals should be empty before seed", preCount.isEmpty())

        val repo = buildCurrentTempRepository(db, context)
        // backfillNwsObservationsIfNeeded calls seed first, then tries NWS network (mocked to null)
        repo.backfillNwsObservationsIfNeeded(TestData.LAT, TestData.LON)

        val postCount = hourlyActualDao.getActualsInRange(
            "2000-01-01T00:00", "2099-12-31T23:00", "NWS", TestData.LAT, TestData.LON,
        )
        assertEquals("Seed should have written 3 actuals from observations", 3, postCount.size)
        assertTrue("All seeded actuals should have source NWS", postCount.all { it.source == "NWS" })
    }

    @Test
    fun `seed does not re-seed when actuals already exist for location`() = runTest {
        val observationDao = db.observationDao()
        val hourlyActualDao = db.hourlyActualDao()

        // Pre-populate 1 actual — seed should not fire
        hourlyActualDao.insertAll(listOf(TestData.hourlyActual(dateTime = "2026-02-19T12:00")))

        // Add 3 observations that would be seeded if seed fires
        val recentTs = System.currentTimeMillis() - 3600_000L
        observationDao.insertAll(listOf(
            TestData.observation(timestamp = recentTs - 7200_000L, temperature = 60f),
            TestData.observation(timestamp = recentTs - 3600_000L, temperature = 62f),
            TestData.observation(timestamp = recentTs,              temperature = 64f),
        ))

        val repo = buildCurrentTempRepository(db, context)
        repo.backfillNwsObservationsIfNeeded(TestData.LAT, TestData.LON)

        val postCount = hourlyActualDao.getActualsInRange(
            "2000-01-01T00:00", "2099-12-31T23:00", "NWS", TestData.LAT, TestData.LON,
        )
        // Seed was skipped; only the pre-existing actual + possibly NWS backfill insertions
        // The NWS backfill is mocked to null (no grid point), so no new actuals from backfill.
        // But observations from the one-month window may also be seeded if actuals were "empty"
        // before the first call. Since we pre-populated 1 actual, seed should have returned early.
        // Result: still 1 actual.
        assertEquals("Seed should not run when actuals already exist", 1, postCount.size)
    }

    // -------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------

    /**
     * Constructs a CurrentTempRepository with real DAOs from [db] and relaxed-mocked network APIs.
     * NwsApi.getGridPoint returns null → backfill exits early after seed, no real network calls.
     */
    private fun buildCurrentTempRepository(db: WeatherDatabase, context: Context): CurrentTempRepository {
        val nwsApi = mockk<NwsApi>(relaxed = true)
        coEvery { nwsApi.getGridPoint(any(), any()) } throws RuntimeException("mocked — no network in tests")

        return CurrentTempRepository(
            context = context,
            currentTempDao = db.currentTempDao(),
            observationDao = db.observationDao(),
            hourlyActualDao = db.hourlyActualDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            appLogDao = db.appLogDao(),
            nwsApi = nwsApi,
            openMeteoApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = WidgetStateManager(context),
            temperatureInterpolator = TemperatureInterpolator(),
        )
    }
}
