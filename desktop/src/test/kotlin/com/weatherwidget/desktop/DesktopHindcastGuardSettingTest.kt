package com.weatherwidget.desktop

import com.weatherwidget.data.model.HindcastGuard
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The temporary hindcast-guard setting (plans/261113-no-hindcasts-unless-prior-missing.md) reaches
 * the shared [HindcastGuard] through the settings publish both desktop processes run on load and
 * on every change. Android twin: `HindcastGuardSettingRoboTest`.
 */
@Category(ShortDuration::class)
class DesktopHindcastGuardSettingTest {
    @After
    fun restore() {
        DesktopActualsPreference.publish(null)
        HindcastGuard.setMaxPriorAgeHours(HindcastGuard.DEFAULT_MAX_PRIOR_AGE_HOURS)
    }

    @Test
    fun `default is 6 hours, matching Android`() {
        assertEquals(6, DesktopSettings().hindcastMaxPriorAgeHours)
    }

    @Test
    fun `publishing a config applies its age to the shared guard`() {
        val config = DesktopConfig(lat = 0.0, lon = 0.0, label = "t", settings = DesktopSettings(hindcastMaxPriorAgeHours = 12))
        DesktopActualsPreference.publish(config)
        assertEquals(12, HindcastGuard.maxPriorAgeHours)

        DesktopActualsPreference.update(config.settings.copy(hindcastMaxPriorAgeHours = 0))
        assertEquals(0, HindcastGuard.maxPriorAgeHours)
    }

    @Test
    fun `the value line matches Android's strings`() {
        assertEquals("Max forecast age 6 h", HindcastGuard.formatMaxPriorAge(6))
        assertEquals("Off — past hours take the newest fetch", HindcastGuard.formatMaxPriorAge(0))
    }
}
