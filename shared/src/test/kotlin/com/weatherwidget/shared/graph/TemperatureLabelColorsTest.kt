package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** The hourly value-label colours both the widget and the desktop graph draw with. */
@Category(ShortDuration::class)
class TemperatureLabelColorsTest {


    @Test
    fun `forecast label is the neutral forecast colour whatever the weather, never rain blue`() {
        // Before 2026-11-15 a rainy hour's label was FORECAST_RAINY (blue).
        assertEquals(WeatherColors.FORECAST_NEUTRAL, TemperatureLabelColors.labelArgb(isFuture = true))
        assertNotEquals(WeatherColors.FORECAST_RAINY, TemperatureLabelColors.labelArgb(isFuture = true))
    }

    @Test
    fun `actual label is the observed rose`() {
        assertEquals(WeatherColors.OBSERVED, TemperatureLabelColors.labelArgb(isFuture = false))
    }

    @Test
    fun `leader is its label's colour at alpha 80`() {
        val label = WeatherColors.FORECAST_SUNNY
        val leader = TemperatureLabelColors.leaderArgb(label)
        assertEquals(80, (leader ushr 24) and 0xFF)
        assertEquals(label and 0xFFFFFF, leader and 0xFFFFFF)
    }
}
