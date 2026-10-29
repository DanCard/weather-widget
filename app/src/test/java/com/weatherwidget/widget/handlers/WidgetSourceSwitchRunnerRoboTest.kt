package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.util.Log
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The widget's API and home buttons switch through the shared [SourceSwitchRunner]: one
 * `SOURCE_SWITCH_E2E` critical line once the widget is painted, then the done line (also in app_logs)
 * after the view tally and the staleness check. A stale home-button intent is a repaint, not a switch.
 * plans/261029-shared-source-switch-runner.md
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSourceSwitchRunnerRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private lateinit var database: WeatherDatabase
    private var widgetId = 0
    private val lines = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // A bound widget, so the paint succeeds (unbound, Robolectric's updateAppWidget throws).
        widgetId = Shadows.shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(WeatherWidgetProvider::class.java, R.layout.widget_weather)
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        ActiveLocationResolver.persist(context, 37.4220, -122.0841)
        RefreshScheduler.setIsRefreshDisabledForTesting(true)
        Log.install { _, _, msg, _ -> if (msg.startsWith(SourceSwitchRunner.E2E_TAG)) lines += msg }
    }

    @After
    fun cleanup() {
        Log.resetToDefault()
        stateManager.clearWidgetState(widgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun persistedRows() = runBlocking { database.appLogDao().getLogsByTag(SourceSwitchRunner.E2E_TAG, 10) }

    @Test
    fun `api button switches through the runner - painted, then counted, checked and logged`() = runBlocking {
        WidgetIntentRouter.handleToggleApi(context, widgetId)

        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(widgetId))
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("SOURCE_SWITCH_E2E platform=android from=NWS to=OPEN_METEO trigger=TOGGLE phase=critical"))
        assertTrue(lines[1], lines[1].contains("to=OPEN_METEO trigger=TOGGLE phase=done"))
        assertTrue(lines[1], Regex("refresh=(QUEUED|FRESH|FAILED)").containsMatchIn(lines[1]))
        assertEquals(1, persistedRows().size)
        assertEquals(1, database.sourceViewDao().getSince(0L).single { it.sourceId == "OPEN_METEO" }.switches)
    }

    @Test
    fun `home button source reset switches through the runner`() = runBlocking {
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.WEATHER_API)
        WidgetIntentRouter.handleResetSource(context, widgetId)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(widgetId))
        assertTrue(lines[0], lines[0].startsWith("SOURCE_SWITCH_E2E platform=android from=WEATHER_API to=NWS trigger=HOME phase=critical"))
        assertTrue(lines[1], lines[1].contains("to=NWS trigger=HOME phase=done"))
        assertEquals("HOME", database.sourceViewDao().getSince(0L).single().triggerKind)
    }

    @Test
    fun `a stale home intent repaints without a switch`() = runBlocking {
        WidgetIntentRouter.handleResetSource(context, widgetId) // already on the preferred source

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(widgetId))
        assertTrue(lines.toString(), lines.isEmpty())
        assertTrue(persistedRows().isEmpty())
    }
}
