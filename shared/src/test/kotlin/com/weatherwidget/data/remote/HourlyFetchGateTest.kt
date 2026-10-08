package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HourlyFetchGateTest {
    private val now = 100 * 3_600_000L
    private val cadence = 4 * 3_600_000L

    @Test
    fun `an unlimited fetch always takes the hours`() {
        assertTrue(HourlyFetchGate.includeHours(false, now, cadence, now))
    }

    @Test
    fun `a limited fetch skips hours that are not yet due`() {
        assertFalse(HourlyFetchGate.includeHours(true, now - 30 * 60_000L, cadence, now))
    }

    @Test
    fun `a limited fetch takes hours that are due by the cadence`() {
        assertTrue(HourlyFetchGate.includeHours(true, now - cadence, cadence, now))
    }

    @Test
    fun `no stored hours are fetched, suspended cadence is not`() {
        assertTrue(HourlyFetchGate.includeHours(true, null, cadence, now))
        assertFalse(HourlyFetchGate.includeHours(true, now - 10 * cadence, null, now))
    }
}
