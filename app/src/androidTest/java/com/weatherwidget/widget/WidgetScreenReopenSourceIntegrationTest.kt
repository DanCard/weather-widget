package com.weatherwidget.widget

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastComparisonActivity
import com.weatherwidget.ui.CurrentObservationsActivity
import com.weatherwidget.widget.handlers.setupCurrentObservationsShortcut
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator bug 2026-10-21: widget on NWS, "Forecast Comparison" opened on Silurian; Observations
 * did the same. The widget's screens have `taskAffinity=""` and read their source only in onCreate,
 * so a NEW_TASK-only launch brought the still-alive task from an earlier open to the front
 * unchanged. Robolectric has no task stack and cannot see this; here the real ActivityManager runs:
 * open on A → Home (the task stays alive) → open on B → the resumed screen must show B.
 *
 * The other half — a flag change not reaching a PendingIntent the launcher still holds — is
 * WidgetActivityPendingIntentFlagsGuardTest (Robolectric reproduces that retention).
 * plans/261021-history-shortcut-showed-previous-source.md
 */
@RunWith(AndroidJUnit4::class)
class WidgetScreenReopenSourceIntegrationTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val widgetId = 990_421
    private val first = WeatherSource.NWS
    private val second = WeatherSource.OPEN_METEO

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
    }

    @After
    fun tearDown() {
        stateManager.clearWidgetState(widgetId)
        finishAll()
    }

    @Test
    fun historyReopenedAfterSourceChangeShowsTheNewSource() {
        val firstScreen = openAndAwait(ForecastComparisonActivity::class.java, first) { openComparison(first) }
        goHome(ForecastComparisonActivity::class.java)
        val secondScreen = openAndAwait(ForecastComparisonActivity::class.java, second) { openComparison(second) }
        assertNotSame("history task was resurfaced instead of recreated", firstScreen, secondScreen)
    }

    @Test
    fun observationsReopenedAfterSourceChangeShowsTheNewSource() {
        val firstScreen = openAndAwait(CurrentObservationsActivity::class.java, first) { openObservations(first) }
        goHome(CurrentObservationsActivity::class.java)
        val secondScreen = openAndAwait(CurrentObservationsActivity::class.java, second) { openObservations(second) }
        assertNotSame("observations task was resurfaced instead of recreated", firstScreen, secondScreen)
    }

    /** The widget's history shortcut: the broadcast it sends; the real receiver builds the activity intent. */
    private fun openComparison(source: WeatherSource) {
        val click = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("date", java.time.LocalDate.now().toString())
            putExtra("showHistory", true)
            putExtra(ForecastComparisonActivity.EXTRA_LAT, TestLocations.LAT)
            putExtra(ForecastComparisonActivity.EXTRA_LON, TestLocations.LON)
            putExtra(ForecastComparisonActivity.EXTRA_SOURCE, source.displayName)
        }
        context.sendBroadcast(click)
    }

    /** The widget's thermometer shortcut: the activity PendingIntent the launcher would send. */
    private fun openObservations(source: WeatherSource) {
        stateManager.setCurrentDisplaySource(widgetId, source)
        val views = object : RemoteViews(context.packageName, R.layout.widget_weather) {
            var pending: PendingIntent? = null
            override fun setOnClickPendingIntent(viewId: Int, pendingIntent: PendingIntent?) {
                if (viewId == R.id.weather_stations_icon) pending = pendingIntent
                super.setOnClickPendingIntent(viewId, pendingIntent)
            }
        }
        setupCurrentObservationsShortcut(context, views, widgetId)
        val pending = checkNotNull(views.pending) { "thermometer shortcut bound no PendingIntent" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pending.send(context, 0, null, null, null, null, options.toBundle())
        } else {
            pending.send()
        }
    }

    private fun <T : Activity> openAndAwait(type: Class<T>, source: WeatherSource, open: () -> Unit): Activity {
        open()
        var shown: String? = null
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val activity = resumed(type)
            shown = activity?.let { onMain { it.findViewById<TextView>(R.id.api_source_button)?.text?.toString() } }
            if (activity != null && shown == source.shortDisplayName) return activity
            SystemClock.sleep(100)
        }
        assertEquals("${type.simpleName} source after opening on ${source.id}", source.shortDisplayName, shown)
        fail("${type.simpleName} never resumed")
        throw AssertionError()
    }

    private fun goHome(type: Class<out Activity>) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (resumed(type) != null) {
            if (SystemClock.uptimeMillis() > deadline) fail("${type.simpleName} still resumed after Home")
            SystemClock.sleep(100)
        }
    }

    private fun resumed(type: Class<out Activity>): Activity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
    }

    private fun finishAll() = onMain {
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).forEach { stage ->
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).forEach { it.finish() }
        }
    }

    private fun <R> onMain(block: () -> R): R {
        var result: Result<R>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
