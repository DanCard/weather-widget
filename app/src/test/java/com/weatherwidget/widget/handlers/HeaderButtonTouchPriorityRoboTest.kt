package com.weatherwidget.widget.handlers

import android.app.Application
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A header button beats any header tap target it overlaps. Touch goes to the last-declared sibling
 * under the finger, so the buttons container must follow current_weather_container (temp and
 * rain-% zones) in widget_root.
 *
 * Emulator 2026-10-22: the rain-% zone ran 443–544 px and the daily home button 471–555 px; a tap
 * on the home icon's centre fired ACTION_TOGGLE_PRECIP and opened the precip view instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@Category(LongDuration::class)
class HeaderButtonTouchPriorityRoboTest {

    @Test
    fun `header buttons are declared after the temp and rain-percent tap targets`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val root = LayoutInflater.from(context).inflate(R.layout.widget_weather, null) as ViewGroup

        val buttons = root.indexOfChild(root.findViewById(R.id.hourly_center_header_container))
        val tapTargets = root.indexOfChild(root.findViewById(R.id.current_weather_container))

        assertTrue("both must be direct children of widget_root", buttons >= 0 && tapTargets >= 0)
        assertTrue(
            "hourly_center_header_container ($buttons) must come after current_weather_container ($tapTargets)",
            buttons > tapTargets,
        )
    }
}
