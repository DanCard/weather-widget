package com.weatherwidget.widget

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class SettingsTouchZoneRoboTest {

    private lateinit var context: Context
    private lateinit var rootView: View

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rootView = LayoutInflater.from(context).inflate(R.layout.widget_weather, null)
    }

    @Test
    fun `settings_touch_zone is declared after nav_right_zone for touch priority`() {
        val root = rootView as ViewGroup
        val settingsContainerIndex = indexOfChild(root, R.id.top_right_header_container)
        val navRightZoneIndex = indexOfChild(root, R.id.nav_right_zone)

        assertTrue("top_right_header_container must be after nav_right_zone in z-order to prevent interception",
            settingsContainerIndex > navRightZoneIndex)
    }

    @Test
    fun `settings_touch_zone is declared after nav_right image button for touch priority`() {
        val root = rootView as ViewGroup
        val settingsContainerIndex = indexOfChild(root, R.id.top_right_header_container)
        val navRightIndex = indexOfChild(root, R.id.nav_right)

        assertTrue("top_right_header_container must be after nav_right in z-order",
            settingsContainerIndex > navRightIndex)
    }

    @Test
    fun `settings_icon is declared after navigation elements`() {
        val root = rootView as ViewGroup
        val settingsContainerIndex = indexOfChild(root, R.id.top_right_header_container)
        val navRightIndex = indexOfChild(root, R.id.nav_right)

        assertTrue("top_right_header_container must be after nav_right in z-order",
            settingsContainerIndex > navRightIndex)
    }

    @Test
    fun `text mode settings touch zone is bottom end and above navigation`() {
        val root = rootView as ViewGroup
        val settingsZone = root.findViewById<View>(R.id.text_mode_settings_touch_zone)
        val params = settingsZone.layoutParams as FrameLayout.LayoutParams
        val density = context.resources.displayMetrics.density
        val navRightZoneIndex = indexOfChild(root, R.id.nav_right_zone)
        val settingsZoneIndex = indexOfChild(root, R.id.text_mode_settings_touch_zone)

        assertEquals(Gravity.BOTTOM or Gravity.END, params.gravity)
        assertEquals(0, params.rightMargin)
        assertEquals(0, params.bottomMargin)
        assertTrue("text mode settings_touch_zone must be after nav_right_zone in z-order",
            settingsZoneIndex > navRightZoneIndex)
    }

    @Test
    fun `text mode api touch zone is center vertical end and above navigation`() {
        val root = rootView as ViewGroup
        val apiZone = root.findViewById<View>(R.id.text_mode_api_touch_zone)
        val params = apiZone.layoutParams as FrameLayout.LayoutParams
        val navRightZoneIndex = indexOfChild(root, R.id.nav_right_zone)
        val apiZoneIndex = indexOfChild(root, R.id.text_mode_api_touch_zone)

        assertEquals(Gravity.CENTER_VERTICAL or Gravity.END, params.gravity)
        assertTrue("text mode api_touch_zone must be after nav_right_zone in z-order",
            apiZoneIndex > navRightZoneIndex)
    }

    @Test
    fun `text mode api indicator is center vertical end and gear stays top end`() {
        val root = rootView as ViewGroup
        val apiParams = root.findViewById<View>(R.id.text_mode_api_source_container)
            .layoutParams as FrameLayout.LayoutParams
        val gearParams = root.findViewById<View>(R.id.text_mode_settings_icon)
            .layoutParams as FrameLayout.LayoutParams

        assertEquals(Gravity.CENTER_VERTICAL or Gravity.END, apiParams.gravity)
        assertEquals(Gravity.TOP or Gravity.END, gearParams.gravity)
    }

    @Test
    fun `text mode api touch zone wins z-order over settings catch-all zone`() {
        val root = rootView as ViewGroup
        val settingsZoneIndex = indexOfChild(root, R.id.text_mode_settings_touch_zone)
        val apiZoneIndex = indexOfChild(root, R.id.text_mode_api_touch_zone)

        assertTrue("api touch zone must be declared after the settings catch-all zone so " +
            "taps on the centred NWS label toggle the source instead of opening settings",
            apiZoneIndex > settingsZoneIndex)
    }

    /**
     * The NWS label and the gear are both anchored to the widget's `end`, so their horizontal
     * spans always overlap; separation is decided purely by the vertical band. Asserting on
     * bands also keeps this test off Robolectric's stub font, which measures text as ~0 wide.
     */
    @Test
    fun `text mode api indicator does not overlap the settings gear`() {
        val root = layOutTextModeWidget()
        val api = rectInRoot(root, root.findViewById(R.id.text_mode_api_source_container))
        val gear = rectInRoot(root, root.findViewById(R.id.text_mode_settings_icon))

        assertTrue("NWS label $api must clear the settings gear $gear vertically",
            api.top >= gear.bottom)
    }

    @Test
    fun `text mode api indicator sits near the vertical middle`() {
        val root = layOutTextModeWidget()
        val apiZone = rectInRoot(root, root.findViewById(R.id.text_mode_api_touch_zone))
        val api = rectInRoot(root, root.findViewById(R.id.text_mode_api_source_container))

        assertTrue("NWS label centre ${api.centerY()} must be in the middle third of " +
            "the ${root.height}px widget",
            api.centerY() in (root.height / 3)..(2 * root.height / 3))
        assertTrue("NWS touch zone centre ${apiZone.centerY()} must track the label " +
            "centre ${api.centerY()}",
            Math.abs(apiZone.centerY() - api.centerY()) <= 2)
    }

    /**
     * Inflates and lays out the widget at a realistic single-row size (4x1 cells) with the
     * text-mode header views made visible, mirroring what [DailyVisibilityManager] does.
     */
    private fun layOutTextModeWidget(): ViewGroup {
        val root = rootView as ViewGroup
        listOf(
            R.id.text_mode_api_source_container,
            R.id.text_mode_api_touch_zone,
            R.id.text_mode_settings_icon,
            R.id.text_mode_settings_touch_zone,
        ).forEach { root.findViewById<View>(it).visibility = View.VISIBLE }

        val density = context.resources.displayMetrics.density
        val widthPx = (WIDGET_WIDTH_DP * density).toInt()
        val heightPx = (WIDGET_HEIGHT_DP * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        return root
    }

    /** Bounds of [view] in [root]'s coordinate space, accumulating each ancestor's offset. */
    private fun rectInRoot(root: ViewGroup, view: View): Rect {
        var dx = 0
        var dy = 0
        var current: View = view
        while (current !== root) {
            dx += current.left
            dy += current.top
            current = current.parent as View
        }
        return Rect(dx, dy, dx + view.width, dy + view.height)
    }

    private fun indexOfChild(parent: ViewGroup, childId: Int): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).id == childId) return i
        }
        return -1
    }

    private companion object {
        const val WIDGET_WIDTH_DP = 320f
        const val WIDGET_HEIGHT_DP = 110f
    }
}
