package com.weatherwidget.widget

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(ShortDuration::class)
class NavTouchZoneRoboTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Set a specific density so dp to px conversion is predictable.
        // Actually, we don't even need to change density, we can just calculate expected px from current density.
    }

    @Test
    fun `nav_left_zone has 40dp width`() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.widget_weather, null)
        val navLeftZone = view.findViewById<FrameLayout>(R.id.nav_left_zone)

        val expectedPx = (40 * context.resources.displayMetrics.density + 0.5f).toInt()
        assertEquals("nav_left_zone width should be 40dp", expectedPx, navLeftZone.layoutParams.width)
    }

    @Test
    fun `nav_right_zone has 40dp width`() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.widget_weather, null)
        val navRightZone = view.findViewById<FrameLayout>(R.id.nav_right_zone)

        val expectedPx = (40 * context.resources.displayMetrics.density + 0.5f).toInt()
        assertEquals("nav_right_zone width should be 40dp", expectedPx, navRightZone.layoutParams.width)
    }
}
