package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopFetchStrategyTest {

    companion object {
        private const val MS_PER_MINUTE = 60 * 1000L
    }

    @Test
    fun `getObservationRefreshDelayMs returns 10 min when charging and screen on`() {
        assertEquals(10 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 20, screenOn = true))
        assertEquals(10 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 90, screenOn = true))
    }

    @Test
    fun `getObservationRefreshDelayMs returns 30 min when charging and screen off`() {
        assertEquals(30 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 20, screenOn = false))
        assertEquals(30 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 90, screenOn = false))
    }

    @Test
    fun `shouldCatchUpObservations returns true when no prior fetch`() {
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = null, nowMs = 1_000_000L))
    }

    @Test
    fun `shouldCatchUpObservations returns false when fetch is recent`() {
        val now = 1_000_000L
        val recentFetch = now - (5 * MS_PER_MINUTE)
        assertEquals(false, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = recentFetch, nowMs = now))
    }

    @Test
    fun `shouldCatchUpObservations returns true when fetch is older than threshold`() {
        val now = 1_000_000L
        val staleFetch = now - (10 * MS_PER_MINUTE)
        val veryStaleFetch = now - (25 * MS_PER_MINUTE)
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = staleFetch, nowMs = now))
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = veryStaleFetch, nowMs = now))
    }

    @Test
    fun `getObservationRefreshDelayMs returns 4 hours when battery above 70`() {
        assertEquals(240 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 71))
    }

    @Test
    fun `getObservationRefreshDelayMs returns 8 hours when battery above 50`() {
        assertEquals(480 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 51))
        assertEquals(480 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 70))
    }

    @Test
    fun `getObservationRefreshDelayMs returns null when battery 50 or below`() {
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 50))
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 10))
    }

    @Test
    fun `getForecastRefreshDelayMs returns 60 min for active source when charging`() {
        assertEquals(60 * MS_PER_MINUTE, DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging = true, batteryLevel = 50, isActiveSource = true))
    }

    @Test
    fun `getForecastRefreshDelayMs returns 120 min for non-active source when charging`() {
        assertEquals(120 * MS_PER_MINUTE, DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging = true, batteryLevel = 50, isActiveSource = false))
    }

    @Test
    fun `getForecastRefreshDelayMs follows same battery tiers as observations`() {
        assertEquals(240 * MS_PER_MINUTE, DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging = false, batteryLevel = 80, isActiveSource = true))
        assertEquals(480 * MS_PER_MINUTE, DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging = false, batteryLevel = 60, isActiveSource = true))
        assertNull(DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging = false, batteryLevel = 40, isActiveSource = true))
    }

    @Test
    fun `getNonPrimaryObservationDelayMs returns 30 min only when charging and screen on`() {
        assertEquals(30 * MS_PER_MINUTE, DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = true, screenOn = true))
    }

    @Test
    fun `getNonPrimaryObservationDelayMs skips when on battery`() {
        assertNull(DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = false, screenOn = true))
    }

    @Test
    fun `getNonPrimaryObservationDelayMs skips when screen off`() {
        assertNull(DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = true, screenOn = false))
        assertNull(DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = false, screenOn = false))
    }
}
