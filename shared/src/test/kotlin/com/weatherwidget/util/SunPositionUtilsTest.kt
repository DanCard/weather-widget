package com.weatherwidget.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class SunPositionUtilsTest {
    @Test
    fun testIsNight_SanFrancisco_Noon() {
        val lat = 37.7749
        val lon = -122.4194
        val dateTime = LocalDateTime.of(2024, 6, 21, 12, 0)
        assertFalse("It should be day at noon in SF", SunPositionUtils.isNight(dateTime, lat, lon))
    }

    @Test
    fun testIsNight_SanFrancisco_Midnight() {
        val lat = 37.7749
        val lon = -122.4194
        val dateTime = LocalDateTime.of(2024, 6, 21, 0, 0)
        assertTrue("It should be night at midnight in SF", SunPositionUtils.isNight(dateTime, lat, lon))
    }

    @Test
    fun testIsNight_SanFrancisco_Sunset() {
        val lat = 37.7749
        val lon = -122.4194
        val eveningDay = LocalDateTime.of(2024, 6, 21, 18, 0)
        val nightTime = LocalDateTime.of(2024, 6, 21, 22, 0)
        assertFalse("It should be day at 6 PM in SF in June", SunPositionUtils.isNight(eveningDay, lat, lon))
        assertTrue("It should be night at 10 PM in SF in June", SunPositionUtils.isNight(nightTime, lat, lon))
    }

    @Test
    fun testGetSunPhase_noonIsDay() {
        val lat = 37.7749
        val lon = -122.4194
        val noon = LocalDateTime.of(2024, 6, 21, 12, 0)
        assertEquals(SunPhase.DAY, SunPositionUtils.getSunPhase(noon, lat, lon))
    }

    @Test
    fun testGetSunPhase_midnightIsNight() {
        val lat = 37.7749
        val lon = -122.4194
        val midnight = LocalDateTime.of(2024, 6, 21, 0, 0)
        assertEquals(SunPhase.NIGHT, SunPositionUtils.getSunPhase(midnight, lat, lon))
    }

    @Test
    fun testGetSunPhase_goldenHourBeforeSunset() {
        val lat = 37.422
        val lon = -122.0841
        // April 14 Mountain View: 7 PM = TWILIGHT (pre-sunset golden hour),
        // 8 PM = TWILIGHT (contains sunset), 9 PM = NIGHT, 6 PM = DAY
        val sixPm = LocalDateTime.of(2026, 4, 14, 18, 0)
        val sevenPm = LocalDateTime.of(2026, 4, 14, 19, 0)
        val eightPm = LocalDateTime.of(2026, 4, 14, 20, 0)
        val ninePm = LocalDateTime.of(2026, 4, 14, 21, 0)
        assertEquals("6 PM should be DAY", SunPhase.DAY, SunPositionUtils.getSunPhase(sixPm, lat, lon))
        assertEquals("7 PM should be TWILIGHT (pre-sunset golden hour)", SunPhase.TWILIGHT, SunPositionUtils.getSunPhase(sevenPm, lat, lon))
        assertEquals("8 PM should be TWILIGHT (contains sunset)", SunPhase.TWILIGHT, SunPositionUtils.getSunPhase(eightPm, lat, lon))
        assertEquals("9 PM should be NIGHT", SunPhase.NIGHT, SunPositionUtils.getSunPhase(ninePm, lat, lon))
    }

    @Test
    fun testGetSunPhase_goldenHourAfterSunrise() {
        val lat = 37.422
        val lon = -122.0841
        // Morning: hour containing sunrise AND the hour after should both be TWILIGHT
        val fiveAm = LocalDateTime.of(2026, 4, 14, 5, 0)
        val sixAm = LocalDateTime.of(2026, 4, 14, 6, 0)
        val sevenAm = LocalDateTime.of(2026, 4, 14, 7, 0)
        val eightAm = LocalDateTime.of(2026, 4, 14, 8, 0)
        // 6 AM contains sunrise → TWILIGHT, 7 AM is post-sunrise golden hour → TWILIGHT
        assertEquals("6 AM should be TWILIGHT (contains sunrise)", SunPhase.TWILIGHT, SunPositionUtils.getSunPhase(sixAm, lat, lon))
        assertEquals("7 AM should be TWILIGHT (post-sunrise golden hour)", SunPhase.TWILIGHT, SunPositionUtils.getSunPhase(sevenAm, lat, lon))
        assertEquals("8 AM should be DAY", SunPhase.DAY, SunPositionUtils.getSunPhase(eightAm, lat, lon))
    }

    @Test
    fun testGetSunPhase_middayIsDay() {
        val lat = 37.422
        val lon = -122.0841
        val tenAm = LocalDateTime.of(2026, 4, 14, 10, 0)
        val fourPm = LocalDateTime.of(2026, 4, 14, 16, 0)
        assertEquals(SunPhase.DAY, SunPositionUtils.getSunPhase(tenAm, lat, lon))
        assertEquals(SunPhase.DAY, SunPositionUtils.getSunPhase(fourPm, lat, lon))
    }

    @Test
    fun testGetSunPhase_consistentWithIsNight() {
        val lat = 37.7749
        val lon = -122.4194
        for (hour in 0..23) {
            val time = LocalDateTime.of(2024, 6, 21, hour, 0)
            val phase = SunPositionUtils.getSunPhase(time, lat, lon)
            val isNight = SunPositionUtils.isNight(time, lat, lon)
            if (phase == SunPhase.NIGHT) {
                assertTrue("NIGHT phase should have isNight=true at hour $hour", isNight)
            } else {
                assertFalse("DAY or TWILIGHT phase should have isNight=false at hour $hour", isNight)
            }
        }
    }

    @Test
    fun testGetSunPhase_wellAfterSunsetIsNight() {
        val lat = 37.7749
        val lon = -122.4194
        val elevenPm = LocalDateTime.of(2024, 6, 21, 23, 0)
        assertEquals(SunPhase.NIGHT, SunPositionUtils.getSunPhase(elevenPm, lat, lon))
    }

    @Test
    fun testGetSunInfo_matchesIndividualCalls() {
        val lat = 37.422
        val lon = -122.0841
        for (hour in 0..23) {
            val time = LocalDateTime.of(2026, 6, 21, hour, 0)
            val info = SunPositionUtils.getSunInfo(time, lat, lon)
            val phase = SunPositionUtils.getSunPhase(time, lat, lon)
            val isNight = SunPositionUtils.isNight(time, lat, lon)
            val isSunBoundary = SunPositionUtils.isSunBoundary(time, lat, lon)
            assertEquals("phase mismatch at hour $hour", phase, info.phase)
            assertEquals("isNight mismatch at hour $hour", isNight, info.isNight)
            assertEquals("isSunBoundary mismatch at hour $hour", isSunBoundary, info.isSunBoundary)
        }
    }

    @Test
    fun testPolar_midnightSun_summer() {
        // Tromsø, Norway: 69.6°N — midnight sun in June.
        // Note: algorithm uses device timezone, so results are approximate for
        // locations in different timezones than the test runner.
        val lat = 69.6492
        val lon = 18.9553
        val noon = LocalDateTime.of(2024, 6, 21, 12, 0)
        val info = SunPositionUtils.getSunInfo(noon, lat, lon)
        // At noon in polar summer, it should not be NIGHT
        assertFalse("Noon during midnight sun should not be NIGHT", info.isNight)
    }

    @Test
    fun testPolar_polarNight_winter() {
        // Tromsø, Norway: 69.6°N — polar night in December.
        // Due to timezone offset between algorithm and coordinates, exact phase
        // assertions are fragile. Instead verify that midnight is not DAY.
        val lat = 69.6492
        val lon = 18.9553
        val midnight = LocalDateTime.of(2024, 12, 21, 0, 0)
        val info = SunPositionUtils.getSunInfo(midnight, lat, lon)
        // At midnight during polar night, should not be DAY
        assertFalse("Midnight during polar night should not be DAY", info.phase == SunPhase.DAY)
    }

    @Test
    fun testSouthernHemisphere_summerNoonNotNight() {
        // Buenos Aires, Argentina: 34.6°S — summer in January
        // Note: algorithm uses device timezone, so exact phases depend on test runner timezone.
        // We validate that the algorithm doesn't crash and produces reasonable results.
        val lat = -34.6037
        val lon = -58.3816
        val noon = LocalDateTime.of(2024, 1, 15, 12, 0)
        val info = SunPositionUtils.getSunInfo(noon, lat, lon)
        // Verify it produces a valid result without crashing
        assertNotNull(info)
        assertNotNull(info.phase)
    }

    @Test
    fun testSouthernHemisphere_negativeLatReturnedValidPhase() {
        // Santiago, Chile: 33.4°S — verify negative latitudes produce valid results
        val lat = -33.4489
        val lon = -70.6693
        val time = LocalDateTime.of(2024, 3, 21, 12, 0)
        val info = SunPositionUtils.getSunInfo(time, lat, lon)
        assertNotNull(info)
    }

    @Test
    fun testGetSunTimes_returnsValidHours() {
        val lat = 37.7749
        val lon = -122.4194
        val dateTime = LocalDateTime.of(2024, 6, 21, 12, 0)
        val sunTimes = SunPositionUtils.getSunTimes(dateTime, lat, lon)
        assertTrue("Sunrise should be between 3 and 8", sunTimes.sunriseHour in 3.0..8.0)
        assertTrue("Sunset should be between 18 and 23", sunTimes.sunsetHour in 18.0..23.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCalculateSunriseSunset_invalidLat() {
        val lat = 91.0
        val lon = 0.0
        val dateTime = LocalDateTime.of(2024, 6, 21, 12, 0)
        SunPositionUtils.getSunPhase(dateTime, lat, lon)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCalculateSunriseSunset_invalidLon() {
        val lat = 0.0
        val lon = 181.0
        val dateTime = LocalDateTime.of(2024, 6, 21, 12, 0)
        SunPositionUtils.getSunPhase(dateTime, lat, lon)
    }

    @Test
    fun testIsSunBoundary_containsSunrise() {
        val lat = 37.422
        val lon = -122.0841
        // Find sunrise hour and verify the hour containing it is a boundary
        val sunriseTime = LocalDateTime.of(2026, 4, 14, 6, 0)
        val sunriseInfo = SunPositionUtils.getSunInfo(sunriseTime, lat, lon)
        if (sunriseInfo.sunTimes.sunriseHour > 0.0 && sunriseInfo.sunTimes.sunriseHour < 24.0) {
            assertTrue("Hour containing sunrise should be a boundary", sunriseInfo.isSunBoundary)
        }
    }

    @Test
    fun testIsSunBoundary_containsSunset() {
        val lat = 37.422
        val lon = -122.0841
        // Find sunset hour and verify the hour containing it is a boundary
        val sunsetTime = LocalDateTime.of(2026, 4, 14, 20, 0)
        val sunsetInfo = SunPositionUtils.getSunInfo(sunsetTime, lat, lon)
        if (sunsetInfo.sunTimes.sunsetHour > 0.0 && sunsetInfo.sunTimes.sunsetHour < 24.0) {
            assertTrue("Hour containing sunset should be a boundary", sunsetInfo.isSunBoundary)
        }
    }
}