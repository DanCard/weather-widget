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
    fun testGetIconResource_ClearDay_HighCloudCover() {
        // Saturday case: Clear text but 79% cloud cover
        val res = WeatherIconMapper.getIconResource("Clear", isNight = false, cloudCover = 79)
        assertEquals(R.drawable.ic_weather_mostly_cloudy, res)
    }

    @Test
    fun testGetIconResource_ClearDay_MidCloudCover() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = false, cloudCover = 50)
        assertEquals(R.drawable.ic_weather_partly_cloudy, res)
    }

    @Test
    fun testGetIconResource_ClearDay_Overcast() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = false, cloudCover = 95)
        assertEquals(R.drawable.ic_weather_cloudy, res)
    }

    @Test
    fun testGetIconResource_ClearDay_MostlyClear() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = false, cloudCover = 10)
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
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, res)
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
    fun testGetIconResource_PatchySnowShowsChanceIconAtModerateProbability() {
        val res = WeatherIconMapper.getIconResource("Patchy Snow", isNight = false, cloudCover = 82, precipProbability = 55)
        assertEquals(R.drawable.ic_weather_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_SlightChanceStormShowsChanceIcon() {
        val res = WeatherIconMapper.getIconResource("Slight Chance Thunderstorms", isNight = true, cloudCover = 80, precipProbability = 55)
        assertEquals(R.drawable.ic_weather_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_SleetShowsSnowIconAtHighProbability() {
        assertEquals(R.drawable.ic_weather_snow, WeatherIconMapper.getIconResource("Sleet", isNight = false, precipProbability = 80))
        assertEquals(R.drawable.ic_weather_snow, WeatherIconMapper.getIconResource("Sleet", isNight = true, precipProbability = 80))
    }

    @Test
    fun testGetIconResource_IcePelletsLowProbabilityFallsBackToCloudCover() {
        assertEquals(
            R.drawable.ic_weather_mostly_clear,
            WeatherIconMapper.getIconResource("Ice Pellets", isNight = false, cloudCover = 5, precipProbability = 5)
        )
    }

    @Test
    fun testGetIconResource_HailShowsStormIconAtHighProbability() {
        assertEquals(R.drawable.ic_weather_storm, WeatherIconMapper.getIconResource("Hail", isNight = false, precipProbability = 80))
        assertEquals(R.drawable.ic_weather_storm, WeatherIconMapper.getIconResource("Hail", isNight = true, precipProbability = 80))
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
    fun testMoonIsSunny() {
        assertTrue(WeatherIconMapper.isSunny(R.drawable.ic_weather_night))
    }

    @Test
    fun testMoonIsNotMixedOrPrecipitation() {
        // Moon should fall through to grey tinting, not skip tinting entirely
        assertFalse(WeatherIconMapper.isMixed(R.drawable.ic_weather_night))
        assertFalse(WeatherIconMapper.isPrecipitation(R.drawable.ic_weather_night))
    }

    @Test
    fun testIsCloudForecastEligible_MostlyClear() {
        assertTrue(WeatherIconMapper.isCloudForecastEligible(R.drawable.ic_weather_mostly_clear))
    }

    @Test
    fun testMostlyClearIsSunnyAndMixed() {
        assertTrue(WeatherIconMapper.isSunny(R.drawable.ic_weather_mostly_clear))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_mostly_clear))
    }

    @Test
    fun testChanceRainMixedIconIsNotPrecipitation() {
        assertFalse(WeatherIconMapper.isPrecipitation(R.drawable.ic_weather_partly_cloudy_chance_rain))
    }

    @Test
    fun testChanceRainMixedIconIsMixedAndCloudEligible() {
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_chance_rain))
        assertTrue(WeatherIconMapper.isCloudForecastEligible(R.drawable.ic_weather_partly_cloudy_chance_rain))
    }

    @Test
    fun testGetIconResource_RainLowProbabilityTrace_ShowsCloudCoverOnly() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 10, precipProbability = 5)
        assertEquals(R.drawable.ic_weather_mostly_clear, res)
    }

    @Test
    fun testGetIconResource_RainLowProbabilityAboveTrace_ShowsOneDrop() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 10, precipProbability = 16)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, res)
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
        // Now 55% is ONE drop (< 60%)
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 90, precipProbability = 55)
        assertEquals(R.drawable.ic_weather_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_RainLikely_ShowsDefinitiveRain() {
        // Now 75% is TWO drops (< 80%)
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 90, precipProbability = 75)
        assertEquals(R.drawable.ic_weather_cloudy_chance_rain, res)
    }

    @Test
    fun testGetIconResource_RainHeavy_ShowsDefinitiveRain() {
        // Now 80% is heavy rain (base icon)
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 90, precipProbability = 80)
        assertEquals(R.drawable.ic_weather_rain, res)
    }

    @Test
    fun testGetIconResource_SunShower_ShowsOneDropClear() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 5, precipProbability = 20)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, res)
    }

    @Test
    fun testGetIconResource_MoonShower_ShowsTwoDropsNight() {
        // Now 55% is ONE drop (< 60%)
        val res = WeatherIconMapper.getIconResource("Rain", isNight = true, cloudCover = 5, precipProbability = 55)
        assertEquals(R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night, res)
    }

    @Test
    fun testGetIconResource_StormLikely_ShowsChanceRainIconUntil80Percent() {
        val res = WeatherIconMapper.getIconResource("Thunderstorms", isNight = false, cloudCover = 50, precipProbability = 75)
        assertEquals(R.drawable.ic_weather_partly_cloudy_chance_rain, res)
    }

    @Test
    fun testGetIconResource_StormHeavy_ShowsStormIconAt80Percent() {
        val res = WeatherIconMapper.getIconResource("Thunderstorms", isNight = false, cloudCover = 50, precipProbability = 80)
        assertEquals(R.drawable.ic_weather_storm, res)
    }

    @Test
    fun testGetIconResource_SnowLikely_ShowsChanceRainIconUntil80Percent() {
        val res = WeatherIconMapper.getIconResource("Snow", isNight = false, cloudCover = 50, precipProbability = 75)
        assertEquals(R.drawable.ic_weather_partly_cloudy_chance_rain, res)
    }

    @Test
    fun testGetIconResource_SnowHeavy_ShowsSnowIconAt80Percent() {
        val res = WeatherIconMapper.getIconResource("Snow", isNight = false, cloudCover = 50, precipProbability = 80)
        assertEquals(R.drawable.ic_weather_snow, res)
    }

    @Test
    fun testNewIconsAreMixed() {
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_slight_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_slight_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_partly_cloudy_chance_rain_night))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_cloudy_slight_chance_rain))
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_cloudy_chance_rain))
    }

    @Test
    fun testSunBoundary_clearReturnsHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = false, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testSunBoundary_sunnyReturnsHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Sunny", isNight = false, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testSunBoundary_fairReturnsHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Fair", isNight = false, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testTwilight_rainUnaffected() {
        val res = WeatherIconMapper.getIconResource("Rain", isNight = false, cloudCover = 50, precipProbability = 80, isTwilight = true)
        assertEquals(R.drawable.ic_weather_rain, res)
    }

    @Test
    fun testTwilight_partlyCloudyUnaffected() {
        val res = WeatherIconMapper.getIconResource("Partly Cloudy", isNight = false, isTwilight = true)
        assertEquals(R.drawable.ic_weather_partly_cloudy, res)
    }

    @Test
    fun testTwilight_clearKeepDayIcon() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = false, isTwilight = true)
        assertEquals(R.drawable.ic_weather_clear, res)
    }

    @Test
    fun testSunBoundary_nightClearOverridesToHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Clear", isNight = true, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testSunBoundary_unknownConditionReturnsHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Something Weird", isNight = false, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testSunBoundary_unknownConditionNightReturnsHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Something Weird", isNight = true, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testHorizonSunIsSunny() {
        assertTrue(WeatherIconMapper.isSunny(R.drawable.ic_weather_horizon_sun))
    }

    @Test
    fun testHorizonSunIsMixed() {
        // Horizon sun is in MIXED_ICONS so it skips flat tinting and uses its internal gradient
        assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_horizon_sun))
    }

    @Test
    fun testTwility_fogUnaffected() {
        val res = WeatherIconMapper.getIconResource("Fog", isNight = false, isTwilight = true)
        assertEquals(R.drawable.ic_weather_fog, res)
    }

    @Test
    fun testSunBoundary_mostlyClearReturnsHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Mostly Clear", isNight = false, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testTwilight_mostlyClearKeepDayIcon() {
        val res = WeatherIconMapper.getIconResource("Mostly Clear", isNight = false, isTwilight = true)
        assertEquals(R.drawable.ic_weather_mostly_clear, res)
    }

    @Test
    fun testSunBoundary_overcastReturnsHorizonSun() {
        val res = WeatherIconMapper.getIconResource("Overcast", isNight = false, isSunBoundary = true)
        assertEquals(R.drawable.ic_weather_horizon_sun, res)
    }

    @Test
    fun testOvercast_dayResolvesToCloudy() {
        val res = WeatherIconMapper.getIconResource("Overcast", isNight = false)
        assertEquals(R.drawable.ic_weather_cloudy, res)
    }

    @Test
    fun testOvercast_nightResolvesToCloudy() {
        val res = WeatherIconMapper.getIconResource("Overcast", isNight = true)
        assertEquals(R.drawable.ic_weather_cloudy, res)
    }
}
