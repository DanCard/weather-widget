package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Cross-path parity regression for the daily cloud-split flap
 * (plans/260710-daily-cloud-cover-flap-stale-fragment.md): given the SAME database — a fresh
 * coordinate site plus a stale fragment from an earlier GPS fix whose frozen forecast disagrees —
 * every DAILY render entry point must resolve the same noon cloud cover.
 *
 * Before the fix, the onUpdate path unified rows to the nearest site (ratio 0.65) while
 * refresh_action_cache_first passed raw proximity-box rows to DailyViewLogic, where
 * DailyNoonCloudCover's firstOrNull picked the stale fragment's noon row (ratio 0.25) — the
 * widget flapped between the two on every update cycle. The stale site's rows are inserted
 * FIRST because the DAO orders by dateTime only, so same-hour cross-site ordering follows
 * rowid: that is the exact list shape that made the stale row win.
 *
 * Observation seam is ShadowLog on DailyViewLogic's permanent debug line
 * ("resolveNoonCloudCoverRatio: date=<d> ... ratio=<r>") — asserting the rendered RemoteViews
 * bitmap would be blind here (Robolectric has no font engine, and the ratio feeds a color
 * split). Asserting the exact 0.65 value (not just leg-equality) is what makes the pre-fix
 * refresh leg fail: it resolved 0.25.
 *
 * Harness follows WeatherWidgetProviderDayTapSourceGapRoboTest (singleton DB seam, static
 * AppWidgetManager mock, provider scope + StandardTestDispatcher).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyCloudCoverSiteParityRoboTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private lateinit var provider: WeatherWidgetProvider
    private lateinit var mockAppWidgetManager: AppWidgetManager
    private val viewsSlot = slot<android.widget.RemoteViews>()
    private val widgetId = 9217

    // Diagnosed device shape: current site + a stale fragment ~0.03° away, both inside the
    // proximity-box query.
    private val freshLat = 37.417
    private val freshLon = -122.089
    private val staleLat = 37.39
    private val staleLon = -122.081
    private val freshNoonCloud = 65
    private val staleNoonCloud = 25

    private val source = WeatherSource.NWS
    private val zone = ZoneId.systemDefault()
    private val targetDate: LocalDate = LocalDate.now().plusDays(3)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)

        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(widgetId, source)
        WidgetIntentRouter.setIsRefreshDisabledForTesting(true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)

        mockAppWidgetManager = mockk()
        // Wide enough for ~10 daily columns so targetDate (today+3) is part of the prepared days.
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 580)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 580)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 187)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 187)
        }
        every { mockAppWidgetManager.getAppWidgetOptions(any()) } returns options
        every { mockAppWidgetManager.getAppWidgetIds(any()) } returns intArrayOf(widgetId)
        every { mockAppWidgetManager.updateAppWidget(any<Int>(), capture(viewsSlot)) } just runs
        every { mockAppWidgetManager.partiallyUpdateAppWidget(any<Int>(), any()) } just runs
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(any()) } returns mockAppWidgetManager

        provider = WeatherWidgetProvider()
        // Dagger injects this on a real broadcast; no observations are seeded, so a relaxed
        // mock (empty lists/maps) keeps the render on the hourly-forecast path under test.
        provider.repository = mockk(relaxed = true)
        // Robolectric's elapsedRealtime starts near 0, so onUpdate's startup debounce
        // (now - last<STARTUP_DEBOUNCE_MS, map default 0) swallows the very FIRST call —
        // advance past the window like a real device's large uptime does.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(5))
        seedTwoSiteDatabase()
    }

    @After
    fun tearDown() {
        stateManager.clearWidgetState(widgetId)
        WidgetIntentRouter.setIsRefreshDisabledForTesting(false)
        db.close()
        WeatherDatabase.resetInstanceForTesting()
        unmockkAll()
    }

    @Test
    fun `refresh path and onUpdate path resolve identical noon cloud from a fragmented cache`() = runTest {
        // Leg A — the previously buggy path: manual-refresh cache-first repaint
        // (WidgetIntentRouter.renderAllWidgetsFromCache → refreshWidget → refreshDailyView).
        ShadowLog.clear()
        WidgetIntentRouter.renderAllWidgetsFromCache(context)
        val refreshRatio = capturedNoonCloudRatio("refresh_action_cache_first leg")

        assertTrue("refresh leg must push RemoteViews", viewsSlot.isCaptured)
        viewsSlot.clear()

        // Leg B — the startup/onUpdate batch path (WeatherWidgetProvider → WidgetRenderer).
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)
        ShadowLog.clear()
        provider.onUpdate(context, mockAppWidgetManager, intArrayOf(widgetId))
        awaitStartupPaintCompletion(testScheduler)

        // Breadcrumb asserts first (repo pattern): a startup paint that errors or is cancelled
        // logs HOURLY_PAINT_TRACE and would otherwise fail this test with an opaque
        // "nothing was logged" message.
        val paintTrace = db.appLogDao().getLogsByTag("HOURLY_PAINT_TRACE", 20)
        assertTrue(
            "onUpdate startup paint must not error/cancel; got ${paintTrace.map { it.message }}",
            paintTrace.none { it.message.contains("startup_ERROR") || it.message.contains("startup_CANCELLED") },
        )
        val onUpdateRatio = capturedNoonCloudRatio("onUpdate leg")

        assertTrue("onUpdate leg must push RemoteViews", viewsSlot.isCaptured)

        // The exact-value asserts are the regression teeth: with the pre-fix code the refresh
        // leg resolved the stale fragment's 0.25 while onUpdate resolved 0.65.
        val expected = freshNoonCloud / 100f
        assertEquals("refresh path must use the fresh site's noon cloud", expected, refreshRatio, 0.001f)
        assertEquals("onUpdate path must use the fresh site's noon cloud", expected, onUpdateRatio, 0.001f)
        assertEquals(
            "all DAILY render paths must resolve the same noon cloud for the same DB",
            refreshRatio,
            onUpdateRatio,
            0.0f,
        )
    }

    /**
     * Drives the virtual test scheduler AND waits for the onUpdate startup paint to finish on
     * Room's real executor threads. advanceUntilIdle() alone returns as soon as the virtual queue is
     * empty — which is the moment the render coroutine first suspends on a DAO read, before the real
     * thread completes it — so under full-suite load the paint could still be in flight when the
     * asserts run. Poll the startup_done / startup_ERROR / startup_CANCELLED breadcrumb that
     * WidgetStartupCoordinator writes AFTER WidgetRenderer.updateWidgetWithData returns, bounded by a
     * real-time deadline, so the test never races an in-flight paint.
     */
    private suspend fun awaitStartupPaintCompletion(testScheduler: TestCoroutineScheduler) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            testScheduler.advanceUntilIdle()
            val trace = db.appLogDao().getLogsByTag("HOURLY_PAINT_TRACE", 20)
            if (trace.any {
                    it.message.startsWith("phase=startup_done") ||
                        it.message.startsWith("phase=startup_ERROR") ||
                        it.message.startsWith("phase=startup_CANCELLED")
                }
            ) {
                return
            }
            Thread.sleep(10)
        }
        testScheduler.advanceUntilIdle()
    }

    /**
     * The parsed value of DailyViewLogic's "resolveNoonCloudCoverRatio: date=<targetDate> ...
     * ratio=<r>" logcat line(s) for this leg. Multiple widgets/renders may log it; they must
     * all agree — disagreement within a leg is the flap itself.
     */
    private fun capturedNoonCloudRatio(leg: String): Float {
        val logicLines = ShadowLog.getLogs().filter { it.tag == "DailyViewLogic" }.map { it.msg }
        val ratios = logicLines.mapNotNull { msg ->
            Regex("resolveNoonCloudCoverRatio: date=$targetDate .*ratio=([0-9.]+)")
                .find(msg)?.groupValues?.get(1)?.toFloat()
        }
        val providerLines = ShadowLog.getLogs()
            .filter { it.tag == "WeatherWidgetProvider" || it.tag == "WidgetRenderer" || it.tag == "DailyViewHandler" }
            .map { "${it.tag}: ${it.msg}" + (it.throwable?.let { t -> " EX=$t" } ?: "") }
        assertTrue(
            "$leg must render $targetDate and log its noon-cloud resolution; " +
                "DailyViewLogic logged: ${logicLines.take(25)}; " +
                "provider chain logged: ${providerLines.take(25)}",
            ratios.isNotEmpty(),
        )
        assertEquals("$leg resolved conflicting ratios for $targetDate: $ratios", 1, ratios.distinct().size)
        return ratios.first()
    }

    /**
     * Stale site inserted FIRST (lower rowids): with `ORDER BY dateTime ASC` its noon row
     * precedes the fresh site's in the raw query result — the ordering that made
     * DailyNoonCloudCover's firstOrNull pick 25 before the fix.
     */
    private fun seedTwoSiteDatabase() = runBlocking {
        val nowFetched = System.currentTimeMillis()
        val staleFetched = nowFetched - 2 * 24 * 3600 * 1000L

        db.hourlyForecastDao().insertAll(
            (0..23).map { h -> hourly(targetDate.atTime(h, 0), staleLat, staleLon, staleNoonCloud, staleFetched) },
        )
        db.hourlyForecastDao().insertAll(
            (0..23).map { h -> hourly(targetDate.atTime(h, 0), freshLat, freshLon, freshNoonCloud, nowFetched) },
        )
        // Today's fresh-site rows so the today column resolves without touching the stale site.
        val nowHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        db.hourlyForecastDao().insertAll(
            (-2L..6L).map { h -> hourly(nowHour.plusHours(h), freshLat, freshLon, 10, nowFetched) },
        )

        db.forecastDao().insertAll(
            listOf(
                forecast(LocalDate.now(), nowFetched),
                forecast(targetDate, nowFetched),
            ),
        )
    }

    private fun hourly(
        time: LocalDateTime,
        lat: Double,
        lon: Double,
        cloud: Int,
        fetchedAt: Long,
    ) = HourlyForecastEntity(
        dateTime = time.atZone(zone).toInstant().toEpochMilli(),
        locationLat = lat,
        locationLon = lon,
        temperature = 70f,
        condition = "Partly Cloudy",
        source = source.id,
        precipProbability = 0,
        cloudCover = cloud,
        fetchedAt = fetchedAt,
    )

    private fun forecast(date: LocalDate, fetchedAt: Long) = ForecastEntity(
        targetDate = date.toEpochDay() * 24 * 60 * 60 * 1000L,
        dateOfPrediction = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L,
        locationLat = freshLat,
        locationLon = freshLon,
        highTemp = 78f,
        lowTemp = 55f,
        condition = "Partly Cloudy",
        source = source.id,
        precipProbability = 0,
        fetchedAt = fetchedAt,
    )
}
