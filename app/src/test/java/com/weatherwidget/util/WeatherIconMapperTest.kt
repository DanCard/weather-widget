package com.weatherwidget.util

import org.junit.Assert.assertEquals
import org.junit.Test
import com.weatherwidget.R
import java.time.LocalDateTime

class WeatherIconMapperTest {

    @Test
    fun testGetIconResource_ClearDay() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = false)
        assertEquals(R.drawable.ic_weather_clear, res)
    }

    @Test
    fun testGetIconResource_ClearNight() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = true)
        assertEquals(R.drawable.ic_weather_night, res)
    }

    @Test
    fun testGetIconResource_PartlyCloudyDay() {
        val res = WeatherIconMapper.getIconResource("Partly Cloudy", isNight = false)
        assertEquals(R.drawable.ic_weather_partly_cloudy, res)
    }

    @Test
    fun testGetIconResource_PartlyCloudyNight() {
        val res = WeatherIconMapper.getIconResource("Partly Cloudy", isNight = true)
        assertEquals(R.drawable.ic_weather_partly_cloudy_night, res)
    }

    @Test
    fun testGetIconResource_Rain() {
        // Rain doesn't change for night currently
        val res = WeatherIconMapper.getIconResource("Rain", isNight = true)
        assertEquals(R.drawable.ic_weather_rain, res)
    }
}
