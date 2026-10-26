package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.util.HeaderButton
import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderFit
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
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
@Config(sdk = [35], application = Application::class)
@Category(LongDuration::class)
class PositionDailyIconsRoboTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun apply(
        placement: HeaderFit.ButtonPlacement,
        showObservations: Boolean,
        widthDp: Int = 440,
        showHome: Boolean = false,
        centreOffsetDp: Float = 0f,
        centreZoneDp: Float = HeaderWidthChecker.centerIconZoneWidthDp(widthDp),
        extraStartDp: Float = 0f,
        extraEndDp: Float = 0f,
    ): View {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        // The daily graph path hides everything first, then re-enables what it wants.
        DailyVisibilityManager.hideUnusedDailyViews(views)
        val want = HeaderButtons.daily(observationsInView = showObservations, home = showHome, months = false)
        val plan = HeaderFit.Plan(
            buttons = want.baseButtons + (if (showHome) 1 else 0),
            homeShown = showHome,
            placement = placement,
        )
        positionDailyIcons(
            views = views,
            placement = placement,
            shown = HeaderButtons.shown(want, plan),
            widthDp = widthDp,
            density = context.resources.displayMetrics.density,
            centre = CentreButtonsLayout(centreOffsetDp, centreZoneDp, extraStartDp, extraEndDp),
        )
        return views.apply(context, FrameLayout(context))
    }

    private fun View.vis(id: Int) = findViewById<View>(id).visibility

    @Test
    fun `center placement shows the floating pair and not the inline pair`() {
        val v = apply(HeaderFit.ButtonPlacement.CENTER, showObservations = true)
        assertEquals(View.VISIBLE, v.vis(R.id.history_icon))
        assertEquals(View.VISIBLE, v.vis(R.id.forecast_history_activity_touch_zone))
        assertEquals(View.VISIBLE, v.vis(R.id.weather_stations_icon))
        assertEquals(View.VISIBLE, v.vis(R.id.weather_stations_touch_zone))
        assertEquals(View.GONE, v.vis(R.id.forecast_history_activity_touch_zone_inline))
        assertEquals(View.GONE, v.vis(R.id.weather_stations_touch_zone_inline))
    }

    @Test
    fun `inline placement shows the inline pair and not the floating pair`() {
        val v = apply(HeaderFit.ButtonPlacement.INLINE, showObservations = true)
        assertEquals(View.VISIBLE, v.vis(R.id.forecast_history_activity_touch_zone_inline))
        assertEquals(View.VISIBLE, v.vis(R.id.weather_stations_touch_zone_inline))
        assertEquals(View.GONE, v.vis(R.id.forecast_history_activity_touch_zone))
        assertEquals(View.GONE, v.vis(R.id.weather_stations_touch_zone))
    }

    @Test
    fun `the observations button drops when today is off screen but history stays`() {
        for (placement in listOf(HeaderFit.ButtonPlacement.CENTER, HeaderFit.ButtonPlacement.INLINE)) {
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
        val v = apply(HeaderFit.ButtonPlacement.HIDDEN, showObservations = true)
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
    fun `the preferred source leaves the home button off, and the selector always`() {
        // The daily view is home in the VIEW sense, and the selector only cycles hourly graphs.
        // positionDailyIcons must not resurrect either from the hourly container it shares.
        for (placement in HeaderFit.ButtonPlacement.entries) {
            val v = apply(placement, showObservations = true, showHome = false)
            for (id in listOf(
                R.id.home_icon,
                R.id.home_touch_zone,
                R.id.home_icon_inline,
                R.id.home_touch_zone_inline,
                R.id.graph_selector_touch_zone,
                R.id.graph_selector_touch_zone_inline,
            )) {
                assertEquals("$placement view $id", View.GONE, v.vis(id))
            }
        }
    }

    @Test
    fun `a non-preferred source turns the home button on in whichever row is drawn`() {
        val centred = apply(HeaderFit.ButtonPlacement.CENTER, showObservations = true, showHome = true)
        assertEquals(View.VISIBLE, centred.vis(R.id.home_icon))
        assertEquals(View.VISIBLE, centred.vis(R.id.home_touch_zone))
        assertEquals(View.GONE, centred.vis(R.id.home_touch_zone_inline))

        val inline = apply(HeaderFit.ButtonPlacement.INLINE, showObservations = true, showHome = true)
        assertEquals(View.VISIBLE, inline.vis(R.id.home_icon_inline))
        assertEquals(View.VISIBLE, inline.vis(R.id.home_touch_zone_inline))
        assertEquals(View.GONE, inline.vis(R.id.home_touch_zone))

        // The ladder never asks for a home button on a hidden row, but a stale header state must
        // not be able to leave one floating alone either.
        val hidden = apply(HeaderFit.ButtonPlacement.HIDDEN, showObservations = true, showHome = true)
        assertEquals(View.GONE, hidden.vis(R.id.home_icon))
        assertEquals(View.GONE, hidden.vis(R.id.home_touch_zone))
        assertEquals(View.GONE, hidden.vis(R.id.home_touch_zone_inline))
    }

    @Test
    fun `the zone ladder gives a mid-width header air between the buttons`() {
        // A 20dp icon in a 24dp zone leaves 4dp of air and the row reads as one control — what a
        // Pixel 7 Pro (373dp, hourly) showed on 2026-10-25. Assert the rungs themselves (the shared
        // HeaderButtonZone air around the 20dp icon), daily and hourly alike, and the 6dp floor.
        // Narrow headers prefer 8dp (2026-10-26); only the squeeze reaches the floor.
        assertEquals(28f, HeaderWidthChecker.centerIconZoneWidthDp(340), 0.01f)
        assertEquals(32f, HeaderWidthChecker.centerIconZoneWidthDp(373), 0.01f)
        assertEquals(40f, HeaderWidthChecker.centerIconZoneWidthDp(440), 0.01f)
        assertEquals(52f, HeaderWidthChecker.centerIconZoneWidthDp(500), 0.01f)
        assertEquals(26f, HeaderWidthChecker.minCenterIconZoneWidthDp(), 0.01f)
    }

    @Test
    fun `the end zones reach into the free space and keep their icons in place`() {
        // A tap just past history (or before the first button) must open it, not fall through to
        // the root's source toggle; the padding keeps each icon centred in its own zone.
        val density = context.resources.displayMetrics.density
        val v = apply(
            HeaderFit.ButtonPlacement.CENTER, showObservations = true, widthDp = 440,
            centreZoneDp = 32f, extraStartDp = 10f, extraEndDp = 20f,
        )
        val obs = v.findViewById<View>(R.id.weather_stations_touch_zone)
        val history = v.findViewById<View>(R.id.forecast_history_activity_touch_zone)
        assertEquals((32f * density).toInt() + (10f * density).toInt(), obs.layoutParams.width)
        assertEquals((10f * density).toInt(), obs.paddingLeft)
        assertEquals(0, obs.paddingRight)
        assertEquals((32f * density).toInt() + (20f * density).toInt(), history.layoutParams.width)
        assertEquals((20f * density).toInt(), history.paddingRight)
        assertEquals(0, history.paddingLeft)
    }

    @Test
    fun `a zone the fit narrowed is the zone laid out`() {
        val density = context.resources.displayMetrics.density
        val v = apply(HeaderFit.ButtonPlacement.CENTER, showObservations = true, widthDp = 440, centreZoneDp = 29f)
        for (id in listOf(R.id.weather_stations_touch_zone, R.id.forecast_history_activity_touch_zone)) {
            assertEquals((29f * density).toInt(), v.findViewById<View>(id).layoutParams.width)
        }
    }

    @Test
    fun `the home zone gets the same width as the buttons beside it`() {
        // Its tap target must match what the fit math reserved: the widget ROOT is bound to
        // ACTION_TOGGLE_API as a Samsung dead-zone backstop, so a tap that misses this zone by a
        // few dp advances the source — the exact opposite of what the button promises.
        val density = context.resources.displayMetrics.density
        for (widthDp in listOf(320, 350, 440, 560)) {
            val v = apply(
                HeaderFit.ButtonPlacement.CENTER,
                showObservations = true,
                widthDp = widthDp,
                showHome = true,
            )
            val expectedPx =
                (HeaderWidthChecker.centerIconZoneWidthDp(widthDp) * density).toInt()
            for (id in listOf(
                R.id.weather_stations_touch_zone,
                R.id.home_touch_zone,
                R.id.forecast_history_activity_touch_zone,
            )) {
                assertEquals(
                    "widthDp=$widthDp view $id",
                    expectedPx,
                    v.findViewById<View>(id).layoutParams.width,
                )
            }
        }
    }

    @Test
    fun `floating and inline are never both visible`() {
        for (placement in HeaderFit.ButtonPlacement.entries) {
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
            val v = apply(HeaderFit.ButtonPlacement.CENTER, showObservations = true, widthDp = widthDp)
            val zone = v.findViewById<View>(R.id.forecast_history_activity_touch_zone)
            val expectedPx =
                (HeaderWidthChecker.centerIconZoneWidthDp(widthDp) * density).toInt()
            assertEquals(
                "widthDp=$widthDp",
                expectedPx,
                zone.layoutParams.width,
            )
        }
    }

    private fun View.containerLeftMargin() =
        (findViewById<View>(R.id.hourly_center_header_container).layoutParams as ViewGroup.MarginLayoutParams).leftMargin

    @Test
    fun `centred buttons move right of the middle by the shared offset`() {
        // The container is centre_horizontal in a FrameLayout, so its left margin is the shift.
        val density = context.resources.displayMetrics.density
        val v = apply(HeaderFit.ButtonPlacement.CENTER, showObservations = true, centreOffsetDp = 37f)
        assertEquals(37f * density, v.containerLeftMargin().toFloat(), 1f)
        // INLINE and HIDDEN leave the container where it was, whatever the offset.
        for (placement in listOf(HeaderFit.ButtonPlacement.INLINE, HeaderFit.ButtonPlacement.HIDDEN)) {
            assertEquals("$placement", 0, apply(placement, showObservations = true, centreOffsetDp = 37f).containerLeftMargin())
        }
    }

    /** The hourly header's four 25dp buttons in a [widthDp] row, on [seam]. */
    private fun hourlyRow(widthDp: Int, seam: HeaderFit.Seam): Pair<HeaderFit.Measured, HeaderFit.Plan> {
        val density = context.resources.displayMetrics.density
        val measured = HeaderFit.Measured(
            width = widthDp * density, rightCluster = 0f, gap = 0f,
            seamOffset = HeaderFit.SEAM_OFFSET_DP * density, baseButtons = 4, centreButton = 25f * density,
        )
        return measured to HeaderFit.Plan(placement = HeaderFit.ButtonPlacement.CENTER, buttons = 4, seam = seam)
    }

    @Test
    fun `the hourly header writes its own offset over the daily one`() {
        // A reapply keeps the margins the daily update set; the hourly view must always set its own.
        val density = context.resources.displayMetrics.density
        val view = apply(HeaderFit.ButtonPlacement.CENTER, showObservations = true, centreOffsetDp = 37f)
        val (middleMeasured, middle) = hourlyRow(440, HeaderFit.Seam.MIDDLE)
        RemoteViews(context.packageName, R.layout.widget_weather).apply {
            positionCenterIcons(this, 440, density, isPrecipVisible = false, measured = middleMeasured, fit = middle, shown = HeaderButtons.shown(HeaderButtons.hourly(true), middle))
        }.reapply(context, view)
        assertEquals(0, view.containerLeftMargin())

        // On the line: four 25dp buttons end 70dp right of the middle, so their centre is 20dp right.
        // The end zones reach unequally into the free space beside them, which moves the
        // container's centre by half the difference — never the buttons.
        val (lineMeasured, line) = hourlyRow(440, HeaderFit.Seam.RIGHT_OF_MIDDLE)
        RemoteViews(context.packageName, R.layout.widget_weather).apply {
            positionCenterIcons(this, 440, density, isPrecipVisible = false, measured = lineMeasured, fit = line, shown = HeaderButtons.shown(HeaderButtons.hourly(true), line))
        }.reapply(context, view)
        val centre = centreButtonsLayout(lineMeasured, line, 440, density)
        assertEquals(20f, centre.offsetDp - (centre.extraEndDp - centre.extraStartDp) / 2f, 0.01f)
        assertEquals(centre.offsetDp * density, view.containerLeftMargin().toFloat(), 1f)
    }

    @Test
    fun `the button views are laid out in HeaderButtons order, floating and inline`() {
        // RemoteViews cannot reorder children, so the layout's child order IS the drawn order;
        // it must match :shared HeaderButtons.ORDER (home first, history last) on both rows.
        val root = RemoteViews(context.packageName, R.layout.widget_weather).apply(context, FrameLayout(context))
        fun order(zoneOf: (HeaderButtonViews) -> Int): List<HeaderButton> {
            val zones = HeaderButtonViews.entries.associateBy { zoneOf(it) }
            val parent = root.findViewById<View>(zoneOf(HeaderButtonViews.HOME)).parent as ViewGroup
            return (0 until parent.childCount).mapNotNull { zones[parent.getChildAt(it).id]?.button }
        }
        assertEquals(HeaderButtons.ORDER, order { it.zone })
        assertEquals(HeaderButtons.ORDER, order { it.inlineZone })
    }

    @Test
    fun `a daily render's months and home do not survive into the hourly header`() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val all = HeaderButtons.daily(observationsInView = true, home = true, months = true)
        val dailyPlan = HeaderFit.Plan(buttons = 4, homeShown = true, monthsShown = true, placement = HeaderFit.ButtonPlacement.INLINE)
        showHeaderButtons(views, HeaderFit.ButtonPlacement.INLINE, HeaderButtons.shown(all, dailyPlan))
        val hourly = HeaderButtons.hourly(observationsInView = false)
        val hourlyPlan = HeaderFit.Plan(buttons = 3, placement = HeaderFit.ButtonPlacement.INLINE)
        showHeaderButtons(views, HeaderFit.ButtonPlacement.INLINE, HeaderButtons.shown(hourly, hourlyPlan))
        val v = views.apply(context, FrameLayout(context))
        assertEquals(View.GONE, v.vis(R.id.months_view_touch_zone_inline))
        assertEquals(View.GONE, v.vis(R.id.weather_stations_touch_zone_inline))
        for (id in listOf(R.id.home_touch_zone_inline, R.id.home_icon_inline, R.id.graph_selector_touch_zone_inline)) {
            assertEquals("view $id", View.VISIBLE, v.vis(id))
        }
    }
}
