package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The forecast refetch matrix both platforms use (user, 2026-10-07): on a charger the displayed
 * source refetches every 4 h (screen on) / 6 h (screen off), other sources 8 h / 12 h; off a charger
 * the battery tiers apply and other sources wait twice as long. No provider appears in the rule.
 */
@Category(ShortDuration::class)
class ForecastCadenceTest {

    private fun interval(charging: Boolean, screenOn: Boolean, displayed: Boolean, battery: Int) =
        ForecastCadence.intervalMinutes(charging, screenOn, displayed, battery)

    @Test
    fun `charger matrix`() {
        assertEquals(240L, interval(true, screenOn = true, displayed = true, battery = 40))
        assertEquals(360L, interval(true, screenOn = false, displayed = true, battery = 40))
        assertEquals(480L, interval(true, screenOn = true, displayed = false, battery = 40))
        assertEquals(720L, interval(true, screenOn = false, displayed = false, battery = 40))
    }

    @Test
    fun `battery at 80 percent is scheduled as charging`() {
        assertEquals(240L, interval(false, screenOn = true, displayed = true, battery = 80))
        assertEquals(720L, interval(false, screenOn = false, displayed = false, battery = 80))
    }

    @Test
    fun `off charger - battery tiers, other sources doubled, screen ignored`() {
        assertEquals(240L, interval(false, screenOn = true, displayed = true, battery = 75))
        assertEquals(240L, interval(false, screenOn = false, displayed = true, battery = 75))
        assertEquals(480L, interval(false, screenOn = true, displayed = false, battery = 75))
        assertEquals(480L, interval(false, screenOn = true, displayed = true, battery = 60))
        assertEquals(960L, interval(false, screenOn = true, displayed = false, battery = 60))
    }

    @Test
    fun `off charger at or below 50 percent - no scheduled fetch for any source`() {
        assertNull(interval(false, screenOn = true, displayed = true, battery = 50))
        assertNull(interval(false, screenOn = true, displayed = false, battery = 30))
    }
}
