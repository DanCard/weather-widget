package com.weatherwidget.widget

import android.content.Context
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
    fun `text mode api touch zone is top end and above navigation`() {
        val root = rootView as ViewGroup
        val apiZone = root.findViewById<View>(R.id.text_mode_api_touch_zone)
        val params = apiZone.layoutParams as FrameLayout.LayoutParams
        val navRightZoneIndex = indexOfChild(root, R.id.nav_right_zone)
        val apiZoneIndex = indexOfChild(root, R.id.text_mode_api_touch_zone)

        assertEquals(Gravity.TOP or Gravity.END, params.gravity)
        assertTrue("text mode api_touch_zone must be after nav_right_zone in z-order",
            apiZoneIndex > navRightZoneIndex)
    }

    private fun indexOfChild(parent: ViewGroup, childId: Int): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).id == childId) return i
        }
        return -1
    }
}
