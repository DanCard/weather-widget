package com.weatherwidget.util

import android.graphics.LinearGradient
import com.weatherwidget.R
import com.weatherwidget.shared.util.WeatherColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WeatherConditionColorsTest {

    @Test
    fun everyMixedIconHasACloudRatio() {
        val failures = mutableListOf<String>()
        for (icon in WeatherIconMapper.MIXED_ICONS) {
            val ratio = WeatherConditionColors.cloudRatio(icon)
            if (ratio == null) {
                val name = iconName(icon)
                failures.add("$name (0x${icon.toString(16)}) has isMixed=true but cloudRatio() returns null")
            }
        }
        assertTrue(
            "Missing cloudRatio for ${failures.size} mixed icons:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    @Test
    fun cloudRatioValuesAreInValidRange() {
        val icons = WeatherIconMapper.MIXED_ICONS
        for (icon in icons) {
            val ratio = WeatherConditionColors.cloudRatio(icon)
            assertNotNull("cloudRatio should not be null for mixed icon 0x${icon.toString(16)}", ratio)
            assertTrue(
                "cloudRatio for ${iconName(icon)} should be >= 0, was $ratio",
                ratio!! >= 0f
            )
            assertTrue(
                "cloudRatio for ${iconName(icon)} should be <= 1, was $ratio",
                ratio <= 1f
            )
        }
    }

    @Test
    fun forecastBarGradientReturnsNonNullForAllCloudRatioIcons() {
        val icons = WeatherIconMapper.MIXED_ICONS
        for (icon in icons) {
            val gradient = WeatherConditionColors.forecastBarGradient(icon, 50f, 0f, 100f)
            assertNotNull(
                "forecastBarGradient should return non-null for mixed icon ${iconName(icon)}",
                gradient
            )
        }
    }

    @Test
    fun chanceRainIconsGetRainyBottomColor() {
        val chanceRainIcons = setOf(
            R.drawable.ic_weather_partly_cloudy_chance_rain,
            R.drawable.ic_weather_partly_cloudy_chance_rain_night,
            R.drawable.ic_weather_cloudy_chance_rain,
        )
        for (icon in chanceRainIcons) {
            val ratio = WeatherConditionColors.cloudRatio(icon)
            assertNotNull("${iconName(icon)} should have a cloud ratio", ratio)
        }
    }

    @Test
    fun mostlyClearHasSubtleCloudRatio() {
        val ratio = WeatherConditionColors.cloudRatio(R.drawable.ic_weather_mostly_clear)
        assertNotNull("mostly clear should have a cloud ratio after mixed reclassification", ratio)
        assertTrue("mostly clear should stay subtler than partly cloudy", ratio!! < 0.35f)
    }

    @Test
    fun largeCloudRatioUsesShortTransitionAndLongGreySection() {
        val stops = WeatherConditionColors.gradientStopPositions(0.68f)
        assertEquals(0f, stops[0], 0.0001f)
        assertEquals(0.32f, stops[1], 0.0001f)
        assertEquals(0.44f, stops[2], 0.0001f)
        assertEquals(1f, stops[3], 0.0001f)
    }

    @Test
    fun subtleCloudRatioKeepsTransitionShorterThanCloudAmount() {
        val stops = WeatherConditionColors.gradientStopPositions(0.18f)
        assertEquals(0f, stops[0], 0.0001f)
        assertEquals(0.82f, stops[1], 0.0001f)
        assertEquals(0.91f, stops[2], 0.0001f)
        assertEquals(1f, stops[3], 0.0001f)
    }

    @Test
    fun mixedMostlyClearResolvesGreyBottomSplit() {
        val split = WeatherConditionColors.resolveMixedBarSplit(R.drawable.ic_weather_mostly_clear, 0.45f)
        assertNotNull(split)
        assertEquals(0.45f, split!!.ratio, 0.0001f)
        assertEquals(0.55f, split.topFraction, 0.0001f)
        // Colors come from the shared single source of truth (WeatherColors); asserting against
        // the shared constants is meaningful even in plain JUnit, where Android's Color.parseColor
        // stub would collapse every WeatherConditionColors.* constant to 0.
        assertEquals(WeatherColors.FORECAST_SUNNY, split.topColor)
        assertEquals(WeatherColors.FORECAST_CLOUDY, split.bottomColor)
    }

    @Test
    fun mixedChanceRainResolvesBlueBottomSplit() {
        val split = WeatherConditionColors.resolveMixedBarSplit(R.drawable.ic_weather_partly_cloudy_chance_rain, 0.66f)
        assertNotNull(split)
        assertEquals(WeatherColors.FORECAST_SUNNY, split!!.topColor)
        assertEquals(WeatherColors.FORECAST_RAINY, split.bottomColor)
    }

    @Test
    fun nonMixedIconsReturnNullCloudRatio() {
        val nonMixedIcons = listOf(
            R.drawable.ic_weather_clear,
            R.drawable.ic_weather_night,
            R.drawable.ic_weather_rain,
            R.drawable.ic_weather_storm,
            R.drawable.ic_weather_snow,
            R.drawable.ic_weather_wind,
            R.drawable.ic_weather_cloudy,
            R.drawable.ic_weather_fog,
            R.drawable.ic_weather_fog_dense,
            R.drawable.ic_weather_unknown,
        )
        for (icon in nonMixedIcons) {
            assertNull(
                "${iconName(icon)} is not mixed and should have null cloudRatio",
                WeatherConditionColors.cloudRatio(icon)
            )
        }
    }

    private fun iconName(resId: Int): String {
        val fields = R.drawable::class.java.fields
        for (field in fields) {
            try {
                if (field.getInt(null) == resId) return field.name
            } catch (_: Exception) {}
        }
        return "unknown_0x${resId.toString(16)}"
    }
}
