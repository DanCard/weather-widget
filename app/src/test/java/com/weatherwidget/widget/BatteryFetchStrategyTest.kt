package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryFetchStrategyTest {

    // ── computeFetchInterval ──

    @Test
    fun `charging always returns 60 regardless of battery level`() {
        assertEquals(60L, BatteryFetchStrategy.computeFetchInterval(isCharging = true, batteryLevel = 10))
        assertEquals(60L, BatteryFetchStrategy.computeFetchInterval(isCharging = true, batteryLevel = 50))
        assertEquals(60L, BatteryFetchStrategy.computeFetchInterval(isCharging = true, batteryLevel = 100))
    }

    @Test
    fun `above 70 returns 120`() {
        assertEquals(120L, BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = 71))
        assertEquals(120L, BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = 100))
    }

    @Test
    fun `51 to 70 returns 240`() {
        assertEquals(240L, BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = 51))
        assertEquals(240L, BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = 70))
    }

    @Test
    fun `50 and below returns null`() {
        assertNull(BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = 50))
        assertNull(BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = 25))
        assertNull(BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = 0))
    }

    // ── shouldRefreshStaleData ──

    @Test
    fun `data older than 4 hours is stale`() {
        val now = System.currentTimeMillis()
        val fiveHoursAgo = now - 5 * 60 * 60 * 1000L
        assertTrue(BatteryFetchStrategy.shouldRefreshStaleData(fiveHoursAgo, now))
    }

    @Test
    fun `data younger than 4 hours is fresh`() {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - 2 * 60 * 60 * 1000L
        assertFalse(BatteryFetchStrategy.shouldRefreshStaleData(twoHoursAgo, now))
    }

    @Test
    fun `exactly 4 hours is not stale`() {
        val now = System.currentTimeMillis()
        val exactlyFourHours = now - 4 * 60 * 60 * 1000L
        assertFalse(BatteryFetchStrategy.shouldRefreshStaleData(exactlyFourHours, now))
    }

    @Test
    fun `null fetchedAt is always stale`() {
        val now = System.currentTimeMillis()
        assertTrue(BatteryFetchStrategy.shouldRefreshStaleData(null, now))
    }

    @Test
    fun `just fetched is fresh`() {
        val now = System.currentTimeMillis()
        assertFalse(BatteryFetchStrategy.shouldRefreshStaleData(now, now))
    }
}
