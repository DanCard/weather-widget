package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.SourceSwitchHostContract
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Android (Room, widget prefs) half of [SourceSwitchHostContract]; the desktop half is `DesktopSourceSwitchHostContractTest` (`:desktop`). */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSourceSwitchHostContractTest : SourceSwitchHostContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stateManager = WidgetStateManager(context)
    private lateinit var database: WeatherDatabase
    private var widgetId = 0

    override fun open(from: WeatherSource, to: WeatherSource, trigger: SourceViewTrigger): SourceSwitchRunner.Host {
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
        RefreshScheduler.setIsRefreshDisabledForTesting(true)
        // A bound widget, so the paint succeeds (unbound, Robolectric's updateAppWidget throws).
        widgetId = Shadows.shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(WeatherWidgetProvider::class.java, R.layout.widget_weather)
        stateManager.clearWidgetState(widgetId)
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN))
        stateManager.setCurrentDisplaySource(widgetId, from)
        ActiveLocationResolver.persist(context, 37.4220, -122.0841)
        val kind = if (trigger == SourceViewTrigger.HOME) {
            WidgetIntentActionHandler.SwitchKind.RESET
        } else {
            WidgetIntentActionHandler.SwitchKind.TOGGLE
        }
        return WidgetIntentActionHandler.WidgetSourceSwitchHost(
            context,
            widgetId,
            repository = null,
            startMs = SystemClock.elapsedRealtime(),
            kind = kind,
        ) {
            stateManager.setCurrentDisplaySource(widgetId, to)
            to
        }
    }

    override fun displayedSourceId(): String = stateManager.getCurrentDisplaySource(widgetId).id

    override suspend fun appLogs(): List<Pair<String, String>> =
        database.appLogDao().getRecentLogs(1_000).sortedBy { it.id }.map { it.tag to it.message }

    override suspend fun viewSwitches(sourceId: String, trigger: SourceViewTrigger): Int =
        database.sourceViewDao().getSince(0L).filter { it.sourceId == sourceId && it.triggerKind == trigger.name }.sumOf { it.switches }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(widgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }
}
