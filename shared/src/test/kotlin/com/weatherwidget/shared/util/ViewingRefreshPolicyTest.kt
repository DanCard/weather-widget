package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ViewingRefreshPolicyTest {
    private val now = 100 * 3_600_000L
    private val cadence = 4 * 3_600_000L
    private val actualsStale = ViewingRefreshPolicy.ACTUALS_STALE_WHILE_VIEWING_MS

    @Test
    fun `screen on with a forecast 30 min old refreshes only actuals`() {
        val d = ViewingRefreshPolicy.decide(now - actualsStale - 1, now - 30 * 60_000L, cadence, now)
        assertEquals(ViewingRefreshPolicy.Decision(refreshActuals = true, refreshForecast = false), d)
    }

    @Test
    fun `screen on with a forecast past the cadence refreshes it`() {
        assertTrue(ViewingRefreshPolicy.decide(now, now - cadence, cadence, now).refreshForecast)
    }

    @Test
    fun `actuals within fifteen minutes are fresh, exactly at the threshold too`() {
        assertFalse(ViewingRefreshPolicy.decide(now - actualsStale, now, cadence, now).refreshActuals)
        assertTrue(ViewingRefreshPolicy.decide(now - actualsStale - 1, now, cadence, now).refreshActuals)
    }

    @Test
    fun `no forecast yet and a suspended cadence are not due`() {
        assertFalse(ViewingRefreshPolicy.decide(now, null, cadence, now).refreshForecast)
        assertFalse(ViewingRefreshPolicy.decide(now, now - 10 * cadence, null, now).refreshForecast)
    }

    @Test
    fun `never-fetched actuals refresh`() {
        assertTrue(ViewingRefreshPolicy.decide(null, now, cadence, now).refreshActuals)
    }
}
