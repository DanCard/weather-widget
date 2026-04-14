package com.weatherwidget.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(ShortDuration::class)
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
        val settingsZoneIndex = indexOfChild(root, R.id.settings_touch_zone)
        val navRightZoneIndex = indexOfChild(root, R.id.nav_right_zone)

        assertTrue("settings_touch_zone must be after nav_right_zone in z-order to prevent interception",
            settingsZoneIndex > navRightZoneIndex)
    }

    @Test
    fun `settings_touch_zone is declared after nav_right image button for touch priority`() {
        val root = rootView as ViewGroup
        val settingsZoneIndex = indexOfChild(root, R.id.settings_touch_zone)
        val navRightIndex = indexOfChild(root, R.id.nav_right)

        assertTrue("settings_touch_zone must be after nav_right in z-order",
            settingsZoneIndex > navRightIndex)
    }

    @Test
    fun `settings_icon is declared after navigation elements`() {
        val root = rootView as ViewGroup
        val settingsIconIndex = indexOfChild(root, R.id.settings_icon)
        val navRightIndex = indexOfChild(root, R.id.nav_right)

        assertTrue("settings_icon must be after nav_right in z-order",
            settingsIconIndex > navRightIndex)
    }

    private fun indexOfChild(parent: ViewGroup, childId: Int): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).id == childId) return i
        }
        return -1
    }
}
