package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A daily home button bound as RESET_SOURCE under an older source order, tapped once the shown
 * source has become the preferred one (Fold 4, 2026-10-22: Google dragged first in Settings while
 * shown). The tap used to log "stale" and return, so the visible button did nothing on every tap.
 * Now it repaints: the push happens and the re-decided header hides the button.
 *
 * Harness follows WeatherWidgetProviderDayTapSourceGapRoboTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ResetSourceStaleButtonRepaintRoboTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private lateinit var receiver: WidgetActionReceiver
    private val pushed = mutableListOf<RemoteViews>()
    private val widgetId = 9131
    private val lat = 37.42
    private val lon = -122.08

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
        ActiveLocationResolver.persist(context, lat, lon)

        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.clearTransientMessage(widgetId)
        // The order after the drag: the shown source is now first.
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.OPEN_METEO, WeatherSource.NWS))
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)
        stateManager.setDateOffset(widgetId, 0)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)

        val manager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 570)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 570)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        every { manager.getAppWidgetOptions(any()) } returns options
        every { manager.getAppWidgetIds(any()) } returns intArrayOf(widgetId)
        every { manager.updateAppWidget(any<Int>(), any<RemoteViews>()) } answers { pushed += secondArg<RemoteViews>() }
        every { manager.partiallyUpdateAppWidget(any<Int>(), any()) } just runs
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(any()) } returns manager

        receiver = WidgetActionReceiver().also {
            it.repository = mockk<WeatherRepository>(relaxed = true)
        }
    }

    @After
    fun tearDown() {
        WidgetActionJobRegistry.clearForTesting()
        db.close()
        WeatherDatabase.resetInstanceForTesting()
        unmockkAll()
    }

    @Test
    fun `stale reset-source tap repaints and the home button goes`() = runTest {
        receiver.scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        seedToday(WeatherSource.OPEN_METEO)

        receiver.onReceive(
            context,
            Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_RESET_SOURCE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            },
        )
        awaitPush(testScheduler)

        assertEquals("source left alone", WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(widgetId))
        assertTrue("a stale tap must repaint (old code returned without a push)", pushed.isNotEmpty())
        val root = pushed.last().apply(context, FrameLayout(context))
        assertEquals(
            "preferred source, today in view: no home button",
            View.GONE,
            root.findViewById<View>(R.id.home_touch_zone).visibility,
        )
    }

    private suspend fun awaitPush(testScheduler: TestCoroutineScheduler) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && pushed.isEmpty()) {
            testScheduler.runCurrent()
            Thread.sleep(10)
        }
        testScheduler.runCurrent()
    }

    private fun seedToday(source: WeatherSource) {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val todayMs = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L
        runBlocking {
            db.hourlyForecastDao().insertAll(
                (-12L..12L).map { h ->
                    HourlyForecastEntity(
                        dateTime = now.plusHours(h).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        locationLat = lat,
                        locationLon = lon,
                        temperature = 60f,
                        condition = "Clear",
                        source = source.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis(),
                    )
                },
            )
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = todayMs,
                        dateOfPrediction = todayMs,
                        locationLat = lat,
                        locationLon = lon,
                        highTemp = 70f,
                        lowTemp = 52f,
                        condition = "Sunny",
                        source = source.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }
}
