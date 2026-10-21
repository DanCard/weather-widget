package com.weatherwidget.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.MonthsViewActivity
import com.weatherwidget.ui.SourceErrorDetailsActivity
import com.weatherwidget.ui.WeatherObservationsActivity
import com.weatherwidget.widget.handlers.setupMonthsViewShortcut
import com.weatherwidget.widget.handlers.setupWeatherStationsShortcut
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Emulator bug 2026-10-21: widget on NWS, history shortcut opened a Silurian history screen.
 * The screens the widget opens have `taskAffinity=""` and read their source only in onCreate, so a
 * launch with NEW_TASK alone brought a still-alive task (opened earlier on another source) to the
 * front unchanged (`START … result code=2`). Observations, Months and Source error details had the
 * same launch. Every widget launch of them must clear that task so the new extras are read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class WidgetScreenLaunchFlagsRoboTest {
    private lateinit var app: Application
    private lateinit var db: WeatherDatabase
    private val widgetId = 7

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
    }

    @After
    fun tearDown() {
        db.close()
        WeatherDatabase.resetInstanceForTesting()
    }

    @Test
    fun `history shortcut replaces an existing history task and carries the displayed source`() {
        val click = Intent(app, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("date", "2026-10-21")
            putExtra("showHistory", true)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, 37.42)
            putExtra(ForecastHistoryActivity.EXTRA_LON, -122.08)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }

        runBlocking { WidgetDayClickCoordinator.handleDayClick(app, click, mockk<WeatherRepository>()) }

        val started = startedActivity(ForecastHistoryActivity::class.java)
        assertEquals(WeatherSource.NWS.displayName, started.getStringExtra(ForecastHistoryActivity.EXTRA_SOURCE))
        assertClearsTask(started)
    }

    @Test
    fun `observations shortcut replaces an existing observations task`() {
        val views = RemoteViews(app.packageName, R.layout.widget_weather)
        setupWeatherStationsShortcut(app, views, widgetId)
        views.apply(app, FrameLayout(app)).findViewById<android.view.View>(R.id.weather_stations_icon).performClick()

        assertClearsTask(startedActivity(WeatherObservationsActivity::class.java))
    }

    @Test
    fun `months shortcut replaces an existing months task and carries the displayed source`() {
        val views = RemoteViews(app.packageName, R.layout.widget_weather)
        setupMonthsViewShortcut(app, views, widgetId, WeatherSource.NWS, 37.42, -122.08)
        views.apply(app, FrameLayout(app)).findViewById<android.view.View>(R.id.months_view_icon).performClick()

        val started = startedActivity(MonthsViewActivity::class.java)
        assertEquals(WeatherSource.NWS.id, started.getStringExtra(MonthsViewActivity.EXTRA_SOURCE))
        assertClearsTask(started)
    }

    @Test
    fun `error details intent replaces an earlier source's details page`() {
        assertClearsTask(SourceErrorDetailsActivity.intent(app, WeatherSource.NWS.id))
    }

    private fun startedActivity(expected: Class<*>): Intent {
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("no activity started", started)
        assertEquals(expected.name, started.component?.className)
        return started
    }

    private fun assertClearsTask(intent: Intent) {
        val hex = "flags=0x${intent.flags.toString(16)}"
        assertTrue("NEW_TASK missing ($hex)", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(
            "CLEAR_TASK missing: a still-alive copy would be shown with its old source ($hex)",
            intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0,
        )
    }
}
