package com.weatherwidget.ui

import android.content.Intent
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.HindcastGuard
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.WidgetStateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The temporary hindcast-guard slider (plans/261113-no-hindcasts-unless-prior-missing.md): the stored
 * age reaches the shared [HindcastGuard] and the Settings slider shows it. Desktop twin:
 * `DesktopHindcastGuardSettingTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
@Category(LongDuration::class)
class HindcastGuardSettingRoboTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun restore() = HindcastGuard.setMaxPriorAgeHours(HindcastGuard.DEFAULT_MAX_PRIOR_AGE_HOURS)

    @Test
    fun `default is 6 hours`() {
        assertEquals(6, WidgetStateManager(context).getHindcastMaxPriorAgeHours())
    }

    @Test
    fun `setting the age stores it and applies it to the shared guard`() {
        val state = WidgetStateManager(context)
        state.setHindcastMaxPriorAgeHours(12)
        assertEquals(12, state.getHindcastMaxPriorAgeHours())
        assertEquals(12, HindcastGuard.maxPriorAgeHours)

        state.setHindcastMaxPriorAgeHours(0)
        assertEquals(0, HindcastGuard.maxPriorAgeHours)
        assertEquals(false, HindcastGuard.enabled)

        state.setHindcastMaxPriorAgeHours(99)
        assertEquals("clamped to the slider's range", 48, state.getHindcastMaxPriorAgeHours())
    }

    @Test
    fun `the Settings slider shows the stored age`() {
        WidgetStateManager(context).setHindcastMaxPriorAgeHours(12)
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).onActivity { activity ->
            val seekBar = activity.findViewById<SeekBar>(R.id.hindcast_guard_seekbar)
            val label = activity.findViewById<TextView>(R.id.hindcast_guard_value)
            println("seekbar=${seekBar.progress}/${seekBar.max} label=${label.text}")
            assertEquals(12, seekBar.progress)
            assertEquals(48, seekBar.max)
            assertEquals("Max forecast age 12 h", label.text.toString())
        }
    }
}
