package com.weatherwidget.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopFetchStrategyTest {

    companion object {
        private const val MS_PER_MINUTE = 60 * 1000L
    }

    @Test
    fun `getObservationRefreshDelayMs returns 10 min when charging`() {
        assertEquals(10 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 20))
        assertEquals(10 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 90))
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
}
