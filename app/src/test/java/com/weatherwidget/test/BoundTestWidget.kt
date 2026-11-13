package com.weatherwidget.test

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.widget.WeatherWidgetProvider
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * A widget that Robolectric's `AppWidgetManager` knows about.
 *
 * `ShadowAppWidgetManager.updateAppWidget` throws `NullPointerException: Cannot read field "layoutId"
 * because "widgetInfo" is null` for an id it did not create. `WidgetInteractionCoordinator.runInteraction`
 * logs and swallows that, so a test using a made-up id (42, 77, ...) passes while the handler stops at
 * its first push and nothing after it runs. Use [create] for the id and [assertNoInteractionFailures]
 * in `@After`, so a swallowed handler failure fails the test instead of only appearing in the log.
 */
object BoundTestWidget {
    private const val COORDINATOR_TAG = "WidgetInteractionCoord"

    fun create(context: Context = ApplicationProvider.getApplicationContext()): Int =
        shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(WeatherWidgetProvider::class.java, R.layout.widget_weather)

    /** Errors `runInteraction` logged and swallowed since the test started. */
    fun interactionFailures(): List<String> =
        ShadowLog.getLogs()
            .filter { it.type == Log.ERROR && it.tag == COORDINATOR_TAG }
            .map { "${it.msg}: ${it.throwable}" }

    fun assertNoInteractionFailures() {
        assertEquals("a widget interaction failed and was swallowed", emptyList<String>(), interactionFailures())
    }
}
