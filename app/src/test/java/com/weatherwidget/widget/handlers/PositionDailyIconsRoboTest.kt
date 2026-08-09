package com.weatherwidget.widget.handlers

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Visibility wiring for the daily header buttons.
 *
 * Exercises [positionDailyIcons] directly rather than through a full widget render: the daily view
 * falls back to TEXT mode without weather data, so a render-level test of the graph path would
 * silently assert nothing. Only dp geometry and visibility are asserted — Robolectric has no font
 * engine (see `robolectric_no_font_engine`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(LongDuration::class)
class PositionDailyIconsRoboTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun apply(
        placement: DailyIconPlacement,
        showObservations: Boolean,
        widthDp: Int = 440,
    ): View {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        // The daily graph path hides everything first, then re-enables what it wants.
        DailyVisibilityManager.hideUnusedDailyViews(views)
        positionDailyIcons(
            views = views,
            placement = placement,
            showObservations = showObservations,
            widthDp = widthDp,
            density = context.resources.displayMetrics.density,
        )
        return views.apply(context, FrameLayout(context))
    }

    private fun View.vis(id: Int) = findViewById<View>(id).visibility

    @Test
    fun `center placement shows the floating pair and not the inline pair`() {
        val v = apply(DailyIconPlacement.CENTER, showObservations = true)
        assertEquals(View.VISIBLE, v.vis(R.id.history_icon))
        assertEquals(View.VISIBLE, v.vis(R.id.forecast_history_activity_touch_zone))
        assertEquals(View.VISIBLE, v.vis(R.id.weather_stations_icon))
        assertEquals(View.VISIBLE, v.vis(R.id.weather_stations_touch_zone))
        assertEquals(View.GONE, v.vis(R.id.forecast_history_activity_touch_zone_inline))
        assertEquals(View.GONE, v.vis(R.id.weather_stations_touch_zone_inline))
    }

    @Test
    fun `inline placement shows the inline pair and not the floating pair`() {
        val v = apply(DailyIconPlacement.INLINE, showObservations = true)
        assertEquals(View.VISIBLE, v.vis(R.id.forecast_history_activity_touch_zone_inline))
        assertEquals(View.VISIBLE, v.vis(R.id.weather_stations_touch_zone_inline))
        assertEquals(View.GONE, v.vis(R.id.forecast_history_activity_touch_zone))
        assertEquals(View.GONE, v.vis(R.id.weather_stations_touch_zone))
    }

    @Test
    fun `the observations button drops when today is off screen but history stays`() {
        for (placement in listOf(DailyIconPlacement.CENTER, DailyIconPlacement.INLINE)) {
            val v = apply(placement, showObservations = false)
            assertEquals("$placement", View.GONE, v.vis(R.id.weather_stations_icon))
            assertEquals("$placement", View.GONE, v.vis(R.id.weather_stations_touch_zone))
            assertEquals("$placement", View.GONE, v.vis(R.id.weather_stations_touch_zone_inline))

            val reachable = v.vis(R.id.forecast_history_activity_touch_zone) == View.VISIBLE ||
                v.vis(R.id.forecast_history_activity_touch_zone_inline) == View.VISIBLE
            assertTrue("history must survive off-today in $placement", reachable)
        }
    }

    @Test
    fun `hidden placement leaves nothing visible`() {
        val v = apply(DailyIconPlacement.HIDDEN, showObservations = true)
        for (id in listOf(
            R.id.history_icon,
            R.id.forecast_history_activity_touch_zone,
            R.id.forecast_history_activity_touch_zone_inline,
            R.id.weather_stations_icon,
            R.id.weather_stations_touch_zone,
            R.id.weather_stations_touch_zone_inline,
        )) {
            assertEquals("view $id", View.GONE, v.vis(id))
        }
    }

    @Test
    fun `the daily view never turns on the home or graph-selector buttons`() {
        // The daily view IS home, and the selector only cycles hourly graphs. positionDailyIcons
        // must not resurrect them from the hourly container it shares.
        for (placement in DailyIconPlacement.entries) {
            val v = apply(placement, showObservations = true)
            for (id in listOf(
                R.id.home_icon,
                R.id.home_touch_zone,
                R.id.home_touch_zone_inline,
                R.id.graph_selector_touch_zone,
                R.id.graph_selector_touch_zone_inline,
            )) {
                assertEquals("$placement view $id", View.GONE, v.vis(id))
            }
        }
    }

    @Test
    fun `floating and inline are never both visible`() {
        for (placement in DailyIconPlacement.entries) {
            for (obs in listOf(true, false)) {
                val v = apply(placement, showObservations = obs)
                assertFalse(
                    "$placement obs=$obs history shown twice",
                    v.vis(R.id.forecast_history_activity_touch_zone) == View.VISIBLE &&
                        v.vis(R.id.forecast_history_activity_touch_zone_inline) == View.VISIBLE,
                )
                assertFalse(
                    "$placement obs=$obs observations shown twice",
                    v.vis(R.id.weather_stations_touch_zone) == View.VISIBLE &&
                        v.vis(R.id.weather_stations_touch_zone_inline) == View.VISIBLE,
                )
            }
        }
    }

    @Test
    fun `narrow headers get the tighter floating zone`() {
        // The applied zone width must equal what the fit math reserved, or the buttons and the
        // date's gap disagree — the bug that pushed the date off a ~350dp widget.
        val density = context.resources.displayMetrics.density
        for (widthDp in listOf(320, 350, 440, 560)) {
            val v = apply(DailyIconPlacement.CENTER, showObservations = true, widthDp = widthDp)
            val zone = v.findViewById<View>(R.id.forecast_history_activity_touch_zone)
            val expectedPx =
                (HeaderWidthChecker.dailyCenterIconZoneWidthDp(widthDp) * density).toInt()
            assertEquals(
                "widthDp=$widthDp",
                expectedPx,
                zone.layoutParams.width,
            )
        }
    }
}
