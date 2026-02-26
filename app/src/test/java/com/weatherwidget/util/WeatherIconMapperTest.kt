package com.weatherwidget.util

import com.weatherwidget.R
import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun testGetIconResource_PartlySunnyDay() {
        val res = WeatherIconMapper.getIconResource("Partly Sunny", isNight = false)
        assertEquals(R.drawable.ic_weather_mostly_clear, res)
    }

    @Test
    fun testGetIconResource_Rain() {
        // Rain doesn't change for night currently
        val res = WeatherIconMapper.getIconResource("Rain", isNight = true)
        assertEquals(R.drawable.ic_weather_rain, res)
    }

    @Test
    fun testGetIconResource_MostlySunny25Percent() {
        val res = WeatherIconMapper.getIconResource("Mostly Sunny (25%)", isNight = false)
        assertEquals(R.drawable.ic_weather_mostly_clear, res)
    }

    @Test
    fun testGetIconResource_MostlyCloudy75Percent() {
        val res = WeatherIconMapper.getIconResource("Mostly Cloudy (75%)", isNight = false)
        assertEquals(R.drawable.ic_weather_partly_cloudy, res)
    }

    @Test
    fun testGetIconResource_MostlyCloudyNight() {
        val res = WeatherIconMapper.getIconResource("Mostly Cloudy", isNight = true)
        assertEquals(R.drawable.ic_weather_mostly_cloudy_night, res)
    }

    @Test
    fun testGetIconResource_BrokenClouds() {
        val res = WeatherIconMapper.getIconResource("Broken Clouds", isNight = false)
        assertEquals(R.drawable.ic_weather_mostly_cloudy, res)
    }

    @Test
    fun testGetIconResource_Fair() {
        // NWS often uses "Fair" for clear/sunny
        val res = WeatherIconMapper.getIconResource("Fair", isNight = false)
        assertEquals(R.drawable.ic_weather_clear, res)
    }

    @Test
    fun testGetIconResource_ObservedFallback() {
        // If we still have "Observed" in the DB, it shouldn't be a cloud
        val res = WeatherIconMapper.getIconResource("Observed", isNight = false)
        assertEquals(R.drawable.ic_weather_clear, res)
    }

    @Test
    fun testGetIconResource_UnknownNoLongerDefaultToCloudy() {
        // Truly random strings should now default to CLEAR (optimistic) rather than CLOUDY
        val res = WeatherIconMapper.getIconResource("Something Weird", isNight = false)
        assertEquals(R.drawable.ic_weather_clear, res)
    }

    @Test
    fun testGetIconResource_SlightChanceRain() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Light Rain", isNight = false)
        assertEquals(R.drawable.ic_weather_partly_cloudy, res)
    }

    @Test
    fun testGetIconResource_PatchySnow() {
        val res = WeatherIconMapper.getIconResource("Patchy Snow", isNight = false)
        assertEquals(R.drawable.ic_weather_partly_cloudy, res)
    }

    @Test
    fun testGetIconResource_SlightChanceStormNight() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Thunderstorms", isNight = true)
        assertEquals(R.drawable.ic_weather_partly_cloudy_night, res)
    }

    @Test
    fun testGetIconResource_FogThenSunny() {
        val res = WeatherIconMapper.getIconResource("Patchy Fog then Partly Sunny", isNight = false)
        assertEquals(R.drawable.ic_weather_fog_sunny, res)
    }
}
