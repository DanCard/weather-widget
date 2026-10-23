package com.weatherwidget.util

import com.weatherwidget.R
import com.weatherwidget.shared.util.WeatherConditionResolver
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

/** Android's icon resources reach the shared daily-bar sky ([WeatherConditionResolver.skyCloudRatio]). */
@Category(ShortDuration::class)
class WeatherConditionColorsTest {

    @Test
    fun everyMixedIconHasASkyInRange() {
        val failures = WeatherIconMapper.MIXED_ICONS.mapNotNull { icon ->
            val ratio = WeatherConditionResolver.skyCloudRatio(WeatherIconMapper.iconResToName(icon))
            if (ratio == null || ratio < 0f || ratio > 1f) "${iconName(icon)} -> $ratio" else null
        }
        assertTrue("Mixed icons without a sky in 0..1:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun plainSkyIconsHaveASky() {
        val icons = listOf(
            R.drawable.ic_weather_clear,
            R.drawable.ic_weather_night,
            R.drawable.ic_weather_cloudy,
            R.drawable.ic_weather_rain,
            R.drawable.ic_weather_fog,
        )
        val failures = icons.filter { WeatherConditionResolver.skyCloudRatio(WeatherIconMapper.iconResToName(it)) == null }
        assertTrue("No sky for: ${failures.map(::iconName)}", failures.isEmpty())
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
