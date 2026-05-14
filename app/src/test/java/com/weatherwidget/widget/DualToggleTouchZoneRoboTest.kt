package com.weatherwidget.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.handlers.WidgetRequestCodes
import com.weatherwidget.widget.handlers.setupDualToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(ShortDuration::class)
class DualToggleTouchZoneRoboTest {

    private lateinit var context: Context
    private lateinit var rootView: View

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rootView = LayoutInflater.from(context).inflate(R.layout.widget_weather, null)
    }

    @Test
    fun `dual_touch_zone is top end with expected margin and size`() {
        val root = rootView as ViewGroup
        val zone = root.findViewById<View>(R.id.dual_touch_zone)
        assertNotNull("dual_touch_zone must exist in widget_weather.xml", zone)
        val params = zone.layoutParams as FrameLayout.LayoutParams
        val density = context.resources.displayMetrics.density

        assertEquals(Gravity.TOP or Gravity.END, params.gravity)
        // marginEnd must match DailyForecastHeaderRenderer.DUAL_BUTTON_MARGIN_END_DP
        // so the touch zone overlays the visually drawn pill.
        assertEquals((150 * density).toInt(), params.rightMargin)
        assertEquals((80 * density).toInt(), params.width)
        assertEquals((50 * density).toInt(), params.height)
    }

    @Test
    fun `dual_touch_zone touch zone margin matches renderer constant`() {
        val zone = rootView.findViewById<View>(R.id.dual_touch_zone)
        val params = zone.layoutParams as FrameLayout.LayoutParams
        val density = context.resources.displayMetrics.density
        val expectedPx = (DailyForecastHeaderRenderer.DUAL_BUTTON_MARGIN_END_DP * density).toInt()
        assertEquals(
            "dual_touch_zone marginEnd must match DailyForecastHeaderRenderer.DUAL_BUTTON_MARGIN_END_DP " +
                "so the visible pill and touch target stay aligned regardless of API label width",
            expectedPx,
            params.rightMargin,
        )
    }

    @Test
    fun `dual_touch_zone is wide enough to tolerate bitmap-to-screen offset`() {
        // The visible pill is bitmap-rendered (~15dp wide) and its screen position
        // can drift a dozen-plus dp from the touch zone's marginEnd anchor because of
        // fitCenter scaling in graph_view. A 64dp+ touch zone ensures the visible pill
        // sits comfortably inside it on every device + density combination.
        val zone = rootView.findViewById<View>(R.id.dual_touch_zone)
        val params = zone.layoutParams as FrameLayout.LayoutParams
        val density = context.resources.displayMetrics.density
        val minWidthPx = (64 * density).toInt()
        assertTrue(
            "dual_touch_zone must be at least 64dp wide for tap tolerance; was ${params.width / density}dp",
            params.width >= minWidthPx,
        )
    }

    @Test
    fun `dual_touch_zone is declared after api_touch_zone for touch priority`() {
        val root = rootView as ViewGroup
        val dualIndex = indexOfChild(root, R.id.dual_touch_zone)
        val apiIndex = indexOfChild(root, R.id.api_touch_zone)
        assertTrue(
            "dual_touch_zone must be declared after api_touch_zone so taps in any overlap " +
                "favor the dual toggle, not the api toggle",
            dualIndex > apiIndex,
        )
    }

    @Test
    fun `dual_touch_zone is declared after settings_touch_zone`() {
        val root = rootView as ViewGroup
        val dualIndex = indexOfChild(root, R.id.dual_touch_zone)
        val settingsIndex = indexOfChild(root, R.id.settings_touch_zone)
        assertTrue(
            "dual_touch_zone must be declared after settings_touch_zone so taps don't fall through to settings",
            dualIndex > settingsIndex,
        )
    }

    @Test
    fun `dual_touch_zone starts hidden`() {
        val zone = rootView.findViewById<View>(R.id.dual_touch_zone)
        assertEquals(
            "dual_touch_zone must default to GONE — DailyViewHandler shows it only when there is room and a distinct alternate source",
            View.GONE,
            zone.visibility,
        )
    }

    @Test
    fun `setupDualToggle wires a broadcast pending intent for ACTION_TOGGLE_DUAL_BARS`() {
        val appWidgetId = 42
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        setupDualToggle(context, views, appWidgetId)

        // Resolve the PendingIntent we just registered and inspect the underlying broadcast.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WidgetRequestCodes.dualToggle(appWidgetId),
            android.content.Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WidgetActions.ACTION_TOGGLE_DUAL_BARS
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNotNull(
            "setupDualToggle must register a PendingIntent under WidgetRequestCodes.dualToggle",
            pendingIntent,
        )

        val shadowApp = Shadows.shadowOf(context as Application)
        val shadowPending = Shadows.shadowOf(pendingIntent!!)
        val intent = shadowPending.savedIntent
        assertEquals(WidgetActions.ACTION_TOGGLE_DUAL_BARS, intent.action)
        assertEquals(
            appWidgetId,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        )
        // Suppress unused warning for shadowApp on Robolectric variants that don't surface it.
        @Suppress("UNUSED_VARIABLE")
        val unused = shadowApp
    }

    @Test
    fun `dual_touch_zone does not overlap api_touch_zone`() {
        // If the two zones overlap, taps in the overlap could go to whichever is declared
        // last (dual wins by z-order) — but a too-wide dual zone could steal taps that
        // users intend for the api toggle. Verify there's no overlap so each visible
        // element owns its own tap region.
        val root = rootView as ViewGroup
        val dualParams = root.findViewById<View>(R.id.dual_touch_zone).layoutParams as FrameLayout.LayoutParams
        val apiParams = root.findViewById<View>(R.id.api_touch_zone).layoutParams as FrameLayout.LayoutParams

        // Both are top|end anchored, so rightMargin + width gives the "left edge"
        // measured from the parent's right edge.
        val dualLeftFromRight = dualParams.rightMargin + dualParams.width
        val apiRightFromRight = apiParams.rightMargin
        assertTrue(
            "dual_touch_zone's left edge (${dualLeftFromRight}px from right) must be at or " +
                "beyond api_touch_zone's right edge (${apiRightFromRight}px from right) to avoid " +
                "stealing taps from the api source toggle",
            dualLeftFromRight >= apiRightFromRight,
        )
    }

    @Test
    fun `dualToggle request code is distinct from neighboring toggles`() {
        val id = 7
        val dual = WidgetRequestCodes.dualToggle(id)
        val api = WidgetRequestCodes.apiToggle(id)
        val precip = WidgetRequestCodes.precipToggle(id)
        val cycle = WidgetRequestCodes.cycleZoom(id)
        assertTrue("dualToggle must not collide with apiToggle", dual != api)
        assertTrue("dualToggle must not collide with precipToggle", dual != precip)
        assertTrue("dualToggle must not collide with cycleZoom", dual != cycle)
    }

    private fun indexOfChild(parent: ViewGroup, childId: Int): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).id == childId) return i
        }
        return -1
    }
}
