package com.weatherwidget.shared.settings

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DisplaySettingsTest {

    @Test
    fun `IntSliderSpec maps SeekBar and Compose slider math consistently`() {
        val spec = DisplaySettings.HEADER_RAIN_LOOKAHEAD
        assertEquals(1, spec.min)
        assertEquals(8, spec.max)
        assertEquals(1, spec.default)
        assertEquals(7, spec.seekBarMax)
        assertEquals(6, spec.composeSteps)
        assertEquals(1f..8f, spec.composeRange)

        for (v in spec.min..spec.max) {
            val progress = spec.toSeekBarProgress(v)
            assertEquals(v, spec.fromSeekBarProgress(progress))
        }
        assertEquals(1, spec.clamp(0))
        assertEquals(8, spec.clamp(99))
        assertEquals(1, spec.fromSeekBarProgress(-3))
        assertEquals(8, spec.fromSeekBarProgress(20))
    }

    @Test
    fun `sanitized clamps every integer slider field into its spec`() {
        val dirty = DisplaySettings(
            narrowZoomSpanHours = 99,
            personalStationDiscount = -5,
            hindcastMaxPriorAgeHours = 999,
            headerRainLookaheadHours = 0,
        )
        val clean = dirty.sanitized()
        assertEquals(DisplaySettings.NARROW_ZOOM_SPAN.max, clean.narrowZoomSpanHours)
        assertEquals(DisplaySettings.PERSONAL_STATION_DISCOUNT.min, clean.personalStationDiscount)
        assertEquals(DisplaySettings.HINDCAST_MAX_PRIOR_AGE.max, clean.hindcastMaxPriorAgeHours)
        assertEquals(DisplaySettings.HEADER_RAIN_LOOKAHEAD.min, clean.headerRainLookaheadHours)
    }

    @Test
    fun `personalStationWeight converts discount percent to IDW weight`() {
        assertEquals(1.0, DisplaySettings(personalStationDiscount = 0).personalStationWeight(), 1e-6)
        assertEquals(0.05, DisplaySettings(personalStationDiscount = 95).personalStationWeight(), 1e-6)
        assertEquals(0.0, DisplaySettings(personalStationDiscount = 100).personalStationWeight(), 1e-6)
    }

    @Test
    fun `diffFrom reports changed fields including headerRainLookaheadHours and showDailyZoomOutButton`() {
        val base = DisplaySettings()
        val updated = base.copy(
            headerRainLookaheadHours = 3,
            showDailyZoomOutButton = true,
        )
        val diff = updated.diffFrom(base)
        println("diff=$diff")
        assertTrue(diff.contains("headerRainLookaheadHours: 1 -> 3"))
        assertTrue(diff.contains("showDailyZoomOutButton: false -> true"))
        assertEquals(2, diff.size)
        assertTrue(base.diffFrom(base).isEmpty())
    }
}
