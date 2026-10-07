package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.shared.util.WeatherConditionResolver.ConditionFlags
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** The hourly value-label colours both the widget and the desktop graph draw with. */
@Category(ShortDuration::class)
class TemperatureLabelColorsTest {

    private val sunny = ConditionFlags(isSunny = true, isRainy = false, isMixed = false)
    private val cloudy = ConditionFlags(isSunny = false, isRainy = false, isMixed = false)
    private val rainy = ConditionFlags(isSunny = false, isRainy = true, isMixed = false)
    private val night = ConditionFlags(isSunny = true, isRainy = false, isMixed = false, isNight = true)
    private val twilight = ConditionFlags(isSunny = true, isRainy = false, isMixed = false, isTwilight = true)

    @Test
    fun `forecast label wears its hour's weather colour, never white`() {
        assertEquals(WeatherColors.FORECAST_SUNNY, TemperatureLabelColors.labelArgb(isFuture = true, sunny))
        assertEquals(WeatherColors.FORECAST_CLOUDY, TemperatureLabelColors.labelArgb(isFuture = true, cloudy))
        assertEquals(WeatherColors.FORECAST_RAINY, TemperatureLabelColors.labelArgb(isFuture = true, rainy))
        assertEquals(WeatherColors.FORECAST_NIGHT, TemperatureLabelColors.labelArgb(isFuture = true, night))
        assertEquals(WeatherColors.FORECAST_TWILIGHT, TemperatureLabelColors.labelArgb(isFuture = true, twilight))
    }

    @Test
    fun `forecast label matches the dashed segment colour for the same hour`() {
        for (flags in listOf(sunny, cloudy, rainy, night, twilight)) {
            assertEquals(
                WeatherColors.forecastColor(flags.isSunny, flags.isRainy, flags.isMixed, flags.isNight, flags.isTwilight),
                TemperatureLabelColors.labelArgb(isFuture = true, flags),
            )
        }
    }

    @Test
    fun `actual label is the observed rose whatever the weather`() {
        for (flags in listOf(sunny, cloudy, rainy, night, twilight)) {
            assertEquals(WeatherColors.OBSERVED, TemperatureLabelColors.labelArgb(isFuture = false, flags))
        }
    }

    @Test
    fun `leader is its label's colour at alpha 80`() {
        val label = WeatherColors.FORECAST_SUNNY
        val leader = TemperatureLabelColors.leaderArgb(label)
        assertEquals(80, (leader ushr 24) and 0xFF)
        assertEquals(label and 0xFFFFFF, leader and 0xFFFFFF)
    }
}
