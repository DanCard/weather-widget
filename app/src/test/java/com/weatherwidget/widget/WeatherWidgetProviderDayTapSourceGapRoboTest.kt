package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.ui.ForecastHistoryActivity
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
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Broadcast-level regression test for the day-tap NPE (2026-07-08): drives the FULL day-tap
 * chain — ACTION_DAY_CLICK intent → WidgetActionReceiver.onReceive → goAsync/launchAsync →
 * WidgetIntentRouter.handleDayClick → WidgetDayClickCoordinator.handleDayClick →
 * WidgetIntentActionHandler.setView → render — against the production crash shape (full
 * OPEN_METEO hourly coverage, NWS missing two hours, NWS displayed).
 *
 * With the old `!!` in computeSmoothedForecasts, setView caught an NPE after flipping the
 * stored view mode: the widget state said TEMPERATURE but no RemoteViews update was ever pushed,
 * so the widget silently stayed on the daily view. Asserting the mode flip alone would therefore
 * pass with the bug — the load-bearing asserts are the captured updateAppWidget call and the
 * DAY_CLICK_RENDER_OK / DAY_CLICK_FAIL app_logs breadcrumbs.
 *
 * Harness follows WeatherWidgetProviderNoHourlyRoboTest (scope seam + StandardTestDispatcher;
 * goAsync returns null on direct onReceive and finishPendingResultSafely tolerates it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetProviderDayTapSourceGapRoboTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private lateinit var receiver: WidgetActionReceiver
    private lateinit var mockAppWidgetManager: AppWidgetManager
    private val viewsSlot = slot<android.widget.RemoteViews>()
    private val widgetId = 9113
    private val lat = 37.42
    private val lon = -122.08
    private val source = WeatherSource.NWS

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)

        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.clearTransientMessage(widgetId)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(widgetId, source)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)

        mockAppWidgetManager = mockk()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
        }
        every { mockAppWidgetManager.getAppWidgetOptions(any()) } returns options
        every { mockAppWidgetManager.getAppWidgetIds(any()) } returns intArrayOf(widgetId)
        every { mockAppWidgetManager.updateAppWidget(any<Int>(), capture(viewsSlot)) } just runs
        every { mockAppWidgetManager.partiallyUpdateAppWidget(any<Int>(), any()) } just runs
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(any()) } returns mockAppWidgetManager

        receiver = WidgetActionReceiver().also {
            it.repository = mockk<WeatherRepository>(relaxed = true)
        }
    }

    @After
    fun tearDown() {
        db.close()
        WeatherDatabase.resetInstanceForTesting()
        unmockkAll()
    }

    @Test
    fun `day tap broadcast renders hourly view despite display-source hour gaps`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        receiver.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        seedSourceGapToday()

        receiver.onReceive(context, dayClickIntent(LocalDate.now()))
        advanceUntilIdle()

        assertEquals(
            "day tap must flip stored view mode to TEMPERATURE",
            ViewMode.TEMPERATURE,
            stateManager.getViewMode(widgetId),
        )

        // Breadcrumb asserts first: DAY_CLICK_FAIL carries the caught exception message, making a
        // failure here self-diagnosing (the old bug surfaced only as a swallowed logcat line).
        val fails = db.appLogDao().getLogsByTag("DAY_CLICK_FAIL", 10)
        assertTrue("no DAY_CLICK_FAIL row expected; got ${fails.map { it.message }}", fails.isEmpty())
        val renderOk = db.appLogDao().getLogsByTag("DAY_CLICK_RENDER_OK", 10)
        assertTrue(
            "DAY_CLICK_RENDER_OK breadcrumb must be persisted; got ${renderOk.map { it.message }}",
            renderOk.any { it.message.contains("widget=$widgetId") && it.message.contains("mode=TEMPERATURE") },
        )
        assertTrue(
            "render must reach updateAppWidget (old bug: NPE aborted before any RemoteViews push)",
            viewsSlot.isCaptured,
        )
    }

    /**
     * Emulator repro shape: OPEN_METEO covers every hour of the resolution window, NWS is
     * missing two mid-window hours. Those buckets pick null for displaySource=NWS — the old
     * code crashed there. A full-coverage fixture would pass even with the bug.
     */
    private fun seedSourceGapToday() {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val nwsGapHours = setOf(-5L, -4L)
        runBlocking {
            db.hourlyForecastDao().insertAll(
                (-12L..3L).flatMap { h ->
                    buildList {
                        add(hourly(now.plusHours(h), 61f, WeatherSource.OPEN_METEO))
                        if (h !in nwsGapHours) add(hourly(now.plusHours(h), 66f, WeatherSource.NWS))
                    }
                },
            )
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L,
                        dateOfPrediction = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L,
                        locationLat = lat,
                        locationLon = lon,
                        highTemp = 78f,
                        lowTemp = 55f,
                        condition = "Sunny",
                        source = source.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    private fun hourly(time: LocalDateTime, temp: Float, src: WeatherSource): HourlyForecastEntity =
        HourlyForecastEntity(
            dateTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Clear",
            source = src.id,
            precipProbability = 0,
            fetchedAt = System.currentTimeMillis(),
        )

    private fun dayClickIntent(targetDay: LocalDate): Intent =
        Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("date", targetDay.toString())
            putExtra("isHistory", false)
            putExtra("showHistory", false)
            putExtra("index", 2)
            putExtra(WidgetActions.EXTRA_TARGET_VIEW, ViewMode.TEMPERATURE.name)
            putExtra(WidgetActions.EXTRA_HOURLY_OFFSET, 0)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, source.displayName)
        }
}
