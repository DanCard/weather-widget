package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class GoogleConditionMapperTest {

    /** The published `WeatherCondition.Type` values (developers.google.com/maps/documentation/weather). */
    private val documented = listOf(
        "CLEAR", "MOSTLY_CLEAR", "PARTLY_CLOUDY", "MOSTLY_CLOUDY", "CLOUDY", "WINDY", "WIND_AND_RAIN",
        "LIGHT_RAIN_SHOWERS", "CHANCE_OF_SHOWERS", "SCATTERED_SHOWERS", "RAIN_SHOWERS",
        "HEAVY_RAIN_SHOWERS", "LIGHT_TO_MODERATE_RAIN", "MODERATE_TO_HEAVY_RAIN", "RAIN", "LIGHT_RAIN",
        "HEAVY_RAIN", "RAIN_PERIODICALLY_HEAVY", "LIGHT_SNOW_SHOWERS", "CHANCE_OF_SNOW_SHOWERS",
        "SCATTERED_SNOW_SHOWERS", "SNOW_SHOWERS", "HEAVY_SNOW_SHOWERS", "LIGHT_TO_MODERATE_SNOW",
        "MODERATE_TO_HEAVY_SNOW", "SNOW", "LIGHT_SNOW", "HEAVY_SNOW", "SNOWSTORM",
        "SNOW_PERIODICALLY_HEAVY", "HEAVY_SNOW_STORM", "BLOWING_SNOW", "RAIN_AND_SNOW", "HAIL",
        "HAIL_SHOWERS", "THUNDERSTORM", "THUNDERSHOWER", "LIGHT_THUNDERSTORM_RAIN",
        "SCATTERED_THUNDERSTORMS", "HEAVY_THUNDERSTORM",
    )

    @Test
    fun `every documented type maps to a known condition`() {
        documented.forEach { type ->
            assertNotEquals(type, "Unknown", WeatherCodeMapper.googleConditionToCondition(type))
        }
    }

    @Test
    fun `families map to the vocabulary the icon resolver matches on`() {
        assertEquals("Clear", WeatherCodeMapper.googleConditionToCondition("CLEAR"))
        assertEquals("Partly Cloudy", WeatherCodeMapper.googleConditionToCondition("PARTLY_CLOUDY"))
        assertEquals("Snow", WeatherCodeMapper.googleConditionToCondition("HEAVY_SNOW_STORM"))
        assertEquals("Thunderstorm", WeatherCodeMapper.googleConditionToCondition("SCATTERED_THUNDERSTORMS"))
        assertEquals("Rain Showers", WeatherCodeMapper.googleConditionToCondition("CHANCE_OF_SHOWERS"))
    }

    @Test
    fun `unspecified and unseen values degrade safely`() {
        assertEquals("Unknown", WeatherCodeMapper.googleConditionToCondition(null))
        assertEquals("Unknown", WeatherCodeMapper.googleConditionToCondition("TYPE_UNSPECIFIED"))
        assertEquals("Rain", WeatherCodeMapper.googleConditionToCondition("FREEZING_RAIN_NEW"))
        assertEquals("Snow", WeatherCodeMapper.googleConditionToCondition("SNOW_GRAINS_NEW"))
    }
}
