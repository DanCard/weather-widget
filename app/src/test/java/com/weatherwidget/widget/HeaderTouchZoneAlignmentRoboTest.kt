package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.widget.handlers.HeaderRemoteViewsBinder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards alignment between the painted Daily header's top-right elements (API label + gear icon)
 * and the XML touch zones laid over them (`api_touch_zone` / `settings_touch_zone`), plus the
 * debug-only [DebugTouchZoneOverlay] tinting lifecycle.
 *
 * Regression: `settings_touch_zone` used to be 44dp wide (`[width-42dp, width+2dp]`), which
 * overlapped the rightmost 8dp of the painted API label (right edge at `width-34dp`), causing
 * taps on the API source label to open Settings instead.
 */
@Category(LongDuration::class)
class HeaderTouchZoneAlignmentRoboTest : RobolectricTest() {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DebugTouchZoneOverlay.resetForTest()
    }

    @After
    fun tearDown() {
        DebugTouchZoneOverlay.resetForTest()
    }

    private fun inflateAndLayout(views: RemoteViews, widthDp: Int = 360, heightDp: Int = 220): View {
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val parent = FrameLayout(context)
        val applied = views.apply(context, parent)
        applied.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        applied.layout(0, 0, widthPx, heightPx)
        return applied
    }

    private fun rectInRoot(root: View, child: View): Rect {
        val rect = Rect(0, 0, child.width, child.height)
        var current: View? = child
        while (current != null && current !== root) {
            rect.offset(current.left, current.top)
            current = current.parent as? View
        }
        return rect
    }

    @Test
    fun `painted daily API label stays inside api_touch_zone and clear of settings_touch_zone`() {
        val widthDp = 360
        val density = context.resources.displayMetrics.density
        val widthPx = widthDp * density

        for (scale in listOf(1.0f, 0.85f)) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            HeaderRemoteViewsBinder.bindApiSource(
                context = context,
                views = views,
                sourceText = "NWS",
                textSizeDp = HeaderConstants.apiTextSizeDp(numRows = 3),
                scale = scale,
            )
            HeaderRemoteViewsBinder.bindScaledIcon(
                context = context,
                views = views,
                viewId = R.id.settings_icon,
                iconRes = R.drawable.ic_settings_gear,
                sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
                scale = scale,
                tintColor = 0xAAFFFFFF.toInt(),
            )
            views.setViewVisibility(R.id.top_right_header_container, View.VISIBLE)
            views.setViewVisibility(R.id.api_touch_zone, View.VISIBLE)
            views.setViewVisibility(R.id.settings_touch_zone, View.VISIBLE)

            val root = inflateAndLayout(views, widthDp = widthDp)
            val apiZone = rectInRoot(root, root.findViewById(R.id.api_touch_zone))
            val settingsZone = rectInRoot(root, root.findViewById(R.id.settings_touch_zone))

            assertFalse("apiZone $apiZone must not overlap settingsZone $settingsZone", Rect.intersects(apiZone, settingsZone))

            // DailyForecastHeaderRenderer draws the API label right-aligned at:
            //   apiX = widthPx - ((API_SOURCE_MARGIN_END_DP + API_SINGLE_SOURCE_EXTRA_MARGIN_DP) * scale - 10f * scale) * density
            val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP + HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP
            val paintedApiRightPx = widthPx - (apiMarginEndDp - 10f) * scale * density
            assertTrue(
                "scale=$scale: painted API right edge ($paintedApiRightPx) must stay inside apiZone $apiZone",
                paintedApiRightPx <= apiZone.right,
            )
            assertTrue(
                "scale=$scale: painted API right edge ($paintedApiRightPx) must stay left of settingsZone $settingsZone",
                paintedApiRightPx <= settingsZone.left,
            )

            // DailyForecastHeaderRenderer draws the gear at [width - (2 + 18)*scale, width - 2*scale].
            val paintedGearRightPx = widthPx - 2f * scale * density
            val paintedGearLeftPx = paintedGearRightPx - HeaderConstants.SETTINGS_ICON_SIZE_DP * scale * density
            assertTrue(
                "scale=$scale: painted gear [$paintedGearLeftPx, $paintedGearRightPx] must stay inside settingsZone $settingsZone",
                paintedGearLeftPx >= settingsZone.left && paintedGearRightPx <= settingsZone.right,
            )
        }
    }

    @Test
    fun `debug touch zone overlay tints zones when enabled and clears them when disabled`() {
        // Off by default: backgrounds stay null / transparent.
        val viewsOff = RemoteViews(context.packageName, R.layout.widget_weather)
        DebugTouchZoneOverlay.apply(viewsOff)
        val rootOff = inflateAndLayout(viewsOff)
        val apiBgOff = (rootOff.findViewById<View>(R.id.api_touch_zone).background as? ColorDrawable)?.color
        assertTrue("default api_touch_zone background should be null or transparent, was $apiBgOff", apiBgOff == null || apiBgOff == Color.TRANSPARENT)

        // Enable overlay: API and Settings get distinct non-transparent tints.
        DebugTouchZoneOverlay.set(context, true)
        assertTrue(DebugTouchZoneOverlay.isEnabled())
        val viewsOn = RemoteViews(context.packageName, R.layout.widget_weather)
        DebugTouchZoneOverlay.apply(viewsOn)
        val rootOn = inflateAndLayout(viewsOn)
        val apiColor = (rootOn.findViewById<View>(R.id.api_touch_zone).background as? ColorDrawable)?.color
        val settingsColor = (rootOn.findViewById<View>(R.id.settings_touch_zone).background as? ColorDrawable)?.color
        assertTrue("api_touch_zone should be tinted when enabled", apiColor != null && apiColor != Color.TRANSPARENT)
        assertTrue("settings_touch_zone should be tinted when enabled", settingsColor != null && settingsColor != Color.TRANSPARENT)
        assertNotEquals("API and Settings touch zones should use distinct colors", apiColor, settingsColor)

        // Disable overlay: next apply explicitly resets backgrounds to transparent.
        DebugTouchZoneOverlay.set(context, false)
        assertFalse(DebugTouchZoneOverlay.isEnabled())
        val viewsCleared = RemoteViews(context.packageName, R.layout.widget_weather)
        DebugTouchZoneOverlay.apply(viewsCleared)
        val rootCleared = inflateAndLayout(viewsCleared)
        assertEquals(
            Color.TRANSPARENT,
            (rootCleared.findViewById<View>(R.id.api_touch_zone).background as? ColorDrawable)?.color,
        )
        assertEquals(
            Color.TRANSPARENT,
            (rootCleared.findViewById<View>(R.id.settings_touch_zone).background as? ColorDrawable)?.color,
        )
    }
}
