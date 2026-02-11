package com.weatherwidget.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SunPositionUtilsTest {
    @Test
    fun testIsNight_SanFrancisco_Noon() {
        // SF Coordinates
        val lat = 37.7749
        val lon = -122.4194

        // Noon in Summer
        val dateTime = LocalDateTime.of(2024, 6, 21, 12, 0)

        assertFalse("It should be day at noon in SF", SunPositionUtils.isNight(dateTime, lat, lon))
    }

    @Test
    fun testIsNight_SanFrancisco_Midnight() {
        val lat = 37.7749
        val lon = -122.4194

        // Midnight
        val dateTime = LocalDateTime.of(2024, 6, 21, 0, 0)

        assertTrue("It should be night at midnight in SF", SunPositionUtils.isNight(dateTime, lat, lon))
    }

    @Test
    fun testIsNight_SanFrancisco_Sunset() {
        val lat = 37.7749
        val lon = -122.4194

        // June 21 sunset in SF is around 8:30 PM (20.5)
        val eveningDay = LocalDateTime.of(2024, 6, 21, 18, 0) // 6 PM
        val nightTime = LocalDateTime.of(2024, 6, 21, 22, 0) // 10 PM

        assertFalse("It should be day at 6 PM in SF in June", SunPositionUtils.isNight(eveningDay, lat, lon))
        assertTrue("It should be night at 10 PM in SF in June", SunPositionUtils.isNight(nightTime, lat, lon))
    }
}
