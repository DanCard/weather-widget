package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WeatherSourceTemperatureActualsTest {

    @Test
    fun `forecast model endpoints do not support temperature actuals`() {
        assertFalse(WeatherSource.SILURIAN.supportsTemperatureActuals)
        assertFalse(WeatherSource.GENERIC_GAP.supportsTemperatureActuals)
    }

    @Test
    fun `verified actual sources remain enabled`() {
        assertTrue(WeatherSource.NWS.supportsTemperatureActuals)
        assertTrue(WeatherSource.OPEN_METEO.supportsTemperatureActuals)
        assertTrue(WeatherSource.WEATHER_API.supportsTemperatureActuals)
        assertTrue(WeatherSource.TOMORROW_IO.supportsTemperatureActuals)
    }
}
