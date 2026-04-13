package com.weatherwidget.util

import com.weatherwidget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
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
    fun testGetIconResource_CloudyWithSubOvercastCloudCoverDay() {
        val res = WeatherIconMapper.getIconResource("Cloudy", isNight = false, cloudCover = 83)
        assertEquals(R.drawable.ic_weather_mostly_cloudy, res)
    }

    @Test
    fun testGetIconResource_CloudyWithSubOvercastCloudCoverNight() {
        val res = WeatherIconMapper.getIconResource("Cloudy", isNight = true, cloudCover = 83)
        assertEquals(R.drawable.ic_weather_mostly_cloudy_night, res)
    }

    @Test
    fun testGetIconResource_CloudyWithNearTotalCloudCoverKeepsCloudy() {
        val res = WeatherIconMapper.getIconResource("Cloudy", isNight = false, cloudCover = 97)
        assertEquals(R.drawable.ic_weather_cloudy, res)
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
    fun testGetIconResource_SlightChanceRainWithLowCloudCoverUsesMostlyClear() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Light Rain", isNight = false, cloudCover = 10)
        assertEquals(R.drawable.ic_weather_clear_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_SlightChanceRainWithMidCloudCoverUsesPartlyCloudy() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Light Rain", isNight = false, cloudCover = 50)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_SlightChanceRainWithHighCloudCoverUsesMostlyCloudy() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Light Rain", isNight = false, cloudCover = 80)
        assertEquals(R.drawable.ic_weather_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_SlightChanceRainWithNearTotalCloudCoverUsesCloudy() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Light Rain", isNight = false, cloudCover = 95)
        assertEquals(R.drawable.ic_weather_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_SlightChanceRainWithoutCloudCoverFallsBackToPartlyCloudy() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Light Rain", isNight = false)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_PatchySnowUsesCloudCoverMapping() {
        val res = WeatherIconMapper.getIconResource("Patchy Snow", isNight = false, cloudCover = 82)
        assertEquals(R.drawable.ic_weather_mostly_cloudy, res)
    }

    @Test
    fun testGetIconResource_SlightChanceStormNightUsesCloudCoverMapping() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Thunderstorms", isNight = true, cloudCover = 80)
        assertEquals(R.drawable.ic_weather_mostly_cloudy_night, res)
    }

    @Test
    fun testGetIconResource_FogThenSunny() {
        val res = WeatherIconMapper.getIconResource("Patchy Fog then Partly Sunny", isNight = false)
        assertEquals(R.drawable.ic_weather_mostly_clear, res)
    }

    @Test
    fun testGetIconResource_PatchyFogWithoutThenUsesLightFog() {
        val res = WeatherIconMapper.getIconResource("Patchy Fog", isNight = false)
        assertEquals(R.drawable.ic_weather_fog_light, res)
        
        val nightRes = WeatherIconMapper.getIconResource("Patchy Fog", isNight = true)
        assertEquals(R.drawable.ic_weather_fog_light_night, nightRes)
    }

    @Test
    fun testGetIconResource_DenseFogUsesDenseFogIcon() {
        val res = WeatherIconMapper.getIconResource("Dense Fog", isNight = false)
        assertEquals(R.drawable.ic_weather_fog_dense, res)
        
        val nightRes = WeatherIconMapper.getIconResource("Dense Fog", isNight = true)
        assertEquals(R.drawable.ic_weather_fog_dense, nightRes)
    }

    @Test
    fun testGetIconResource_AreasOfFogUsesStandardFog() {
        val res = WeatherIconMapper.getIconResource("Areas of Fog", isNight = false)
        assertEquals(R.drawable.ic_weather_fog, res)
        
        val nightRes = WeatherIconMapper.getIconResource("Areas of Fog", isNight = true)
        assertEquals(R.drawable.ic_weather_fog_night, nightRes)
    }

    @Test
    fun testGetIconResource_MistHazeUsesStandardFog() {
        assertEquals(R.drawable.ic_weather_fog, WeatherIconMapper.getIconResource("Mist", isNight = false))
        assertEquals(R.drawable.ic_weather_fog, WeatherIconMapper.getIconResource("Haze", isNight = false))
    }

    @Test
    fun testMoonIsNotSunny() {
        // Clear night uses the moon icon — should NOT be classified as sunny (would tint it gold)
        assertFalse(WeatherIconMapper.isSunny(R.drawable.ic_weather_night))
    }

    @Test
    fun testMoonIsNotMixedOrRainy() {
        // Moon should fall through to grey tinting, not skip tinting entirely
        assertFalse(WeatherIconMapper.isMixed(R.drawable.ic_weather_night))
        assertFalse(WeatherIconMapper.isRainy(R.drawable.ic_weather_night))
    }

    @Test
    fun testIsCloudForecastEligible_MostlyClear() {
        assertTrue(WeatherIconMapper.isCloudForecastEligible(R.drawable.ic_weather_mostly_clear))
    }

    @Test
    fun testChanceRainMixedIconIsNotRainy() {
        assertFalse(WeatherIconMapper.isRainy(R.drawable.ic_weather_partly_cloudy_chance_rain))
    }

    @Test
    fun testChanceRainMixedIconIsMixedAndCloudEligible() {
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_chance_rain))
        assertTrue(WeatherIconMapper.isCloudForecastEligible(R.drawable.ic_weather_partly_cloudy_chance_rain))
    }

    @Test
    fun testGetIconResource_RainLowProbabilityTrace_ShowsCloudCoverOnly() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 10, precipProbability = 15)
        assertEquals(R.drawable.ic_weather_mostly_clear, res)
    }

    @Test
    fun testGetIconResource_RainLowProbabilityAboveTrace_ShowsOneDrop() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 10, precipProbability = 16)
        assertEquals(R.drawable.ic_weather_clear_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_RainSlightChance_ShowsOneDropPartlyCloudy() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 50, precipProbability = 20)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_RainSlightChanceNight_ShowsOneDropPartlyCloudyNight() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = true, cloudCover = 50, precipProbability = 20)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night, res)
    }

    @Test
    fun testGetIconResource_RainChance_ShowsTwoDropsCloudy() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 90, precipProbability = 40)
        assertEquals(R.drawable.ic_weather_cloudy_chance_rain, res)
    }

    @Test
    fun testGetIconResource_RainLikely_ShowsDefinitiveRain() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 90, precipProbability = 60)
        assertEquals(R.drawable.ic_weather_rain, res)
    }

    @Test
    fun testGetIconResource_SunShower_ShowsOneDropClear() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 5, precipProbability = 20)
        assertEquals(R.drawable.ic_weather_clear_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_MoonShower_ShowsTwoDropsNight() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = true, cloudCover = 5, precipProbability = 40)
        assertEquals(R.drawable.ic_weather_night_chance_rain, res)
    }

    @Test
    fun testNewIconsAreMixed() {
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_slight_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_clear_slight_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_clear_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_night_slight_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_night_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_cloudy_slight_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_cloudy_chance_rain))
    }
}
