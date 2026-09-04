package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.WeatherConditionResolver.IC_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_HORIZON_SUN
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_MOSTLY_CLEAR
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_MOSTLY_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_MOSTLY_CLOUDY_NIGHT
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_NIGHT
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_PARTLY_CLOUDY
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the hourly-cloud-percent-first rule for the fully-cloudy condition words: worded "overcast"
 * and plain "cloudy" must not override the measured (hourly) cloud cover once it drops below
 * [WeatherConditionResolver.FULLY_CLOUDY_THRESHOLD]% — the cover percent picks the graded tier via
 * `getCloudCoverIcon`. The threshold-qualified words ("mostly/partly cloudy") keep their own
 * branches, and ≥ threshold / null-cover days keep trusting the word.
 */
@Category(ShortDuration::class)
class CloudWordHourlyCoverDowngradeTest {

    private fun resolve(condition: String, cover: Int?, night: Boolean = false) =
        WeatherConditionResolver.resolveIconName(condition, isNight = night, cloudCover = cover)

    // ── "Overcast" grades down with the measured cover ──

    @Test
    fun overcastNearClearCoverResolvesToMostlyClear() {
        assertEquals(IC_MOSTLY_CLEAR, resolve("Overcast", 2))
        assertEquals(IC_MOSTLY_CLEAR, resolve("Overcast", 0))
    }

    @Test
    fun overcastMidCoverResolvesToPartlyCloudy() {
        assertEquals(IC_PARTLY_CLOUDY, resolve("Overcast", 50))
    }

    @Test
    fun overcastHighCoverResolvesToMostlyCloudy() {
        assertEquals(IC_MOSTLY_CLOUDY, resolve("Overcast", 83))
    }

    @Test
    fun overcastAtSubThresholdEdgeStaysCloudy() {
        // 91–96% is already the cloudy band of getCloudCoverIcon.
        assertEquals(IC_CLOUDY, resolve("Overcast", 95))
    }

    @Test
    fun overcastAtOrAboveFullyCloudyThresholdStaysCloudy() {
        assertEquals(IC_CLOUDY, resolve("Overcast", 97))
        assertEquals(IC_CLOUDY, resolve("Overcast", 100))
    }

    @Test
    fun overcastWithoutMeasuredCoverStaysCloudy() {
        // No measurement → trust the provider's wording.
        assertEquals(IC_CLOUDY, resolve("Overcast", null))
    }

    @Test
    fun overcastNightGradesThroughNightTiers() {
        assertEquals(IC_NIGHT, resolve("Overcast", 2, night = true))
        assertEquals(IC_MOSTLY_CLOUDY_NIGHT, resolve("Overcast", 83, night = true))
    }

    @Test
    fun overcastAtSunBoundaryKeepsHorizonSun() {
        assertEquals(
            IC_HORIZON_SUN,
            WeatherConditionResolver.resolveIconName(
                "Overcast",
                isNight = false,
                cloudCover = 2,
                isSunBoundary = true,
            ),
        )
    }

    // ── Plain "cloudy" grades down the same way ──

    @Test
    fun cloudyNearClearCoverResolvesToMostlyClear() {
        assertEquals(IC_MOSTLY_CLEAR, resolve("Cloudy", 2))
    }

    @Test
    fun cloudyMidCoverResolvesToPartlyCloudy() {
        assertEquals(IC_PARTLY_CLOUDY, resolve("Cloudy", 50))
    }

    @Test
    fun cloudyHighCoverStillResolvesToMostlyCloudy() {
        assertEquals(IC_MOSTLY_CLOUDY, resolve("Cloudy", 83))
    }

    @Test
    fun cloudyNightNearClearCoverResolvesToClearNight() {
        assertEquals(IC_NIGHT, resolve("Cloudy", 10, night = true))
    }

    // ── Qualified / precipitation words keep their own branches ──

    @Test
    fun mostlyCloudyWordIsExcludedFromTheDowngrade() {
        assertEquals(IC_PARTLY_CLOUDY, resolve("Mostly Cloudy", 2))
    }

    @Test
    fun partlyCloudyWordIsExcludedFromTheDowngrade() {
        assertEquals(IC_PARTLY_CLOUDY, resolve("Partly Cloudy", 2))
    }

    @Test
    fun rainWordStillOutranksCloudCover() {
        assertEquals(
            WeatherConditionResolver.IC_RAIN,
            resolve("Cloudy with Rain", 2),
        )
    }
}
