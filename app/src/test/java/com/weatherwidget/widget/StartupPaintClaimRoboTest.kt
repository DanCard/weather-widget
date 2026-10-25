package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * [StartupPaintClaims] through the real [WidgetStartupCoordinator] and a real Room database: which
 * startup batches push a paint, and what they log.
 *
 * Fold 4, 2026-10-17: `onUpdate` painted at 06:51:06; the unfold at 06:51:27 re-added the widgets
 * with no views and sent `onUpdate` again, which was refused (`via=on_update holder=on_update`) and
 * left the inner widget on "Today --°" (plans/261017-startup-paint-claim-skips-host-rebind.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class StartupPaintClaimRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val widgetId = 345
    private var clockMs = 1_000_000L
    private lateinit var appWidgetManager: AppWidgetManager

    @Before
    fun setUp(): Unit = runBlocking {
        StartupPaintClaims.clearForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val db = WeatherDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.forecastDao().insertAll(
            listOf(
                ForecastEntity(
                    targetDate = LocalDate.now().toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    dateOfPrediction = now,
                    highTemp = 60f,
                    lowTemp = 45f,
                    condition = "Clear",
                    source = WeatherSource.OPEN_METEO.id,
                    locationLat = 37.42,
                    locationLon = -122.08,
                    fetchedAt = now,
                    lastConfirmedAt = now,
                ),
            ),
        )
        appWidgetManager = mockk(relaxed = true)
        every { appWidgetManager.getAppWidgetOptions(any()) } returns
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 662)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 662)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 465)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 465)
            }
    }

    @After
    fun tearDown() {
        StartupPaintClaims.clearForTests()
        WeatherDatabase.resetInstanceForTesting()
    }

    private fun startup(via: String, atMs: Long) = runBlocking {
        clockMs = atMs
        WidgetStartupCoordinator(mockk(relaxed = true), elapsedRealtime = { clockMs }).updateWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = intArrayOf(widgetId),
            startupToken = "test-$via-$atMs",
            onUpdateStartMs = atMs,
            claimVia = via,
        )
    }

    private fun logCount(tag: String): Int = runBlocking {
        WeatherDatabase.getDatabase(context).appLogDao().getLogsByTag(tag, 100).size
    }

    @Test
    fun `a repeat onUpdate inside the window paints again`() {
        val pushed = mutableListOf<Long>()
        every { appWidgetManager.updateAppWidget(widgetId, any<RemoteViews>()) } answers { pushed += clockMs }

        startup(WidgetStartupCoordinator.STARTUP_CLAIM_VIA, atMs = 1_000_000L)
        val afterFirst = pushed.size
        startup(WidgetStartupCoordinator.STARTUP_CLAIM_VIA, atMs = 1_022_000L)

        assertTrue("the first onUpdate paints", afterFirst > 0)
        assertTrue("the unfold's onUpdate paints too, pushed=$pushed", pushed.any { it == 1_022_000L })
        assertEquals(0, logCount("STARTUP_PAINT_SKIP"))
        assertEquals(1, logCount("STARTUP_PAINT_REPEAT"))
    }

    @Test
    fun `the partner trigger inside the window is still deduped, once`() {
        val pushed = mutableListOf<Long>()
        every { appWidgetManager.updateAppWidget(widgetId, any<RemoteViews>()) } answers { pushed += clockMs }

        startup(PackageReplacedReceiver.STARTUP_CLAIM_VIA, atMs = 1_000_000L)
        startup(WidgetStartupCoordinator.STARTUP_CLAIM_VIA, atMs = 1_004_000L)
        assertFalse("the partner's paint is dropped, pushed=$pushed", pushed.any { it == 1_004_000L })
        assertEquals(1, logCount("STARTUP_PAINT_SKIP"))

        // A host rebind after that, still inside the window, paints.
        startup(WidgetStartupCoordinator.STARTUP_CLAIM_VIA, atMs = 1_010_000L)
        assertTrue("the rebind after the dedupe paints, pushed=$pushed", pushed.any { it == 1_010_000L })
        assertEquals(1, logCount("STARTUP_PAINT_SKIP"))
    }
}
