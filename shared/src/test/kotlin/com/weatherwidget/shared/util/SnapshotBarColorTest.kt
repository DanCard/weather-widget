package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the daily bar colours both platforms take from :shared. A cloudy snapshot must NOT grey the
 * base — grey base + grey cloud share rendered the whole bar as a solid-grey slab.
 */
@Category(ShortDuration::class)
class SnapshotBarColorTest {

    @Test
    fun rainySnapshotKeepsBlue() {
        assertEquals(WeatherColors.FORECAST_RAINY, WeatherColors.snapshotBarArgb(WeatherConditionResolver.IC_RAIN))
    }

    @Test
    fun anyOtherSnapshotIsYellow() {
        assertEquals(WeatherColors.SNAPSHOT_YELLOW, WeatherColors.snapshotBarArgb(WeatherConditionResolver.IC_CLOUDY))
        assertEquals(WeatherColors.SNAPSHOT_YELLOW, WeatherColors.snapshotBarArgb(null))
    }

    @Test
    fun forecastColourFollowsTheIconAndTheDaytimeRainGate() {
        assertEquals(WeatherColors.FORECAST_RAINY, WeatherColors.dailyForecastArgb(WeatherConditionResolver.IC_RAIN, 70))
        // Rain overnight only: not a rainy day.
        assertEquals(WeatherColors.FORECAST_CLOUDY, WeatherColors.dailyForecastArgb(WeatherConditionResolver.IC_RAIN, 15))
        assertEquals(WeatherColors.FORECAST_SUNNY, WeatherColors.dailyForecastArgb(WeatherConditionResolver.IC_CLEAR, null))
        assertEquals(WeatherColors.FORECAST_SUNNY, WeatherColors.dailyForecastArgb(WeatherConditionResolver.IC_PARTLY_CLOUDY, null))
        assertEquals(WeatherColors.FORECAST_CLOUDY, WeatherColors.dailyForecastArgb(WeatherConditionResolver.IC_CLOUDY, null))
        assertEquals(WeatherColors.FORECAST_CLOUDY, WeatherColors.dailyForecastArgb(null, null))
    }
}
