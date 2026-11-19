package com.weatherwidget.ui

import android.content.Intent
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.shared.settings.DisplaySettings
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WidgetStateManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Header rain lookahead slider (1–8h, default 6h) backed by the shared [DisplaySettings] spec.
 * Desktop twin: `DesktopHeaderRainLookaheadTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
@Category(LongDuration::class)
class HeaderRainLookaheadRoboTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `default is 6 hours and clamping matches shared DisplaySettings spec`() {
        val state = WidgetStateManager(context)
        assertEquals(6, state.getHeaderRainLookaheadHours())
        assertEquals(6, state.getDisplaySettings().headerRainLookaheadHours)

        state.setHeaderRainLookaheadHours(0)
        assertEquals(DisplaySettings.HEADER_RAIN_LOOKAHEAD.min, state.getHeaderRainLookaheadHours())

        state.setHeaderRainLookaheadHours(99)
        assertEquals(DisplaySettings.HEADER_RAIN_LOOKAHEAD.max, state.getHeaderRainLookaheadHours())

        state.setHeaderRainLookaheadHours(3)
        assertEquals(3, state.getHeaderRainLookaheadHours())
        assertEquals(3, state.getDisplaySettings().headerRainLookaheadHours)
    }

    @Test
    fun `Settings slider maps 1h and 8h through shared IntSliderSpec`() {
        val state = WidgetStateManager(context)
        state.setHeaderRainLookaheadHours(1)
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val seekBar = activity.findViewById<SeekBar>(R.id.header_rain_lookahead_seekbar)
                val label = activity.findViewById<TextView>(R.id.header_rain_lookahead_value)
                println("1h: seekbar=${seekBar.progress}/${seekBar.max} label=${label.text}")
                assertEquals(0, seekBar.progress)
                assertEquals(7, seekBar.max)
                assertEquals("Next 1 hour", label.text.toString())
            }
        }

        state.setHeaderRainLookaheadHours(8)
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val seekBar = activity.findViewById<SeekBar>(R.id.header_rain_lookahead_seekbar)
                val label = activity.findViewById<TextView>(R.id.header_rain_lookahead_value)
                println("8h: seekbar=${seekBar.progress}/${seekBar.max} label=${label.text}")
                assertEquals(7, seekBar.progress)
                assertEquals(7, seekBar.max)
                assertEquals("Next 8 hours", label.text.toString())
            }
        }
    }
}
