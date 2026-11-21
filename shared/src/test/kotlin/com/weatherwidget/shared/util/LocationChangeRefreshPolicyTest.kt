package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class LocationChangeRefreshPolicyTest {
    private val hour = 3_600_000L
    private val now = 1_795_280_000_000L

    private fun decide(ageMs: Long?, highBattery: Boolean = true, drawable: Boolean = true) =
        LocationChangeRefreshPolicy.decide(
            drawableCache = drawable,
            lastForecastFetchMs = ageMs?.let { now - it },
            nowMs = now,
            isHighBattery = highBattery,
        ).also { println(it.logLine("NWS")) }

    @Test
    fun `a site fetched 86 minutes ago keeps its cache (2026-11-21 Mountain View)`() {
        assertFalse(decide(86 * 60_000L).fetchForecast)
    }

    @Test
    fun `past two hours at high battery the move refetches`() {
        assertTrue(decide(3 * hour).fetchForecast)
    }

    @Test
    fun `at low battery the limit is four hours`() {
        assertFalse(decide(3 * hour, highBattery = false).fetchForecast)
        assertTrue(decide(5 * hour, highBattery = false).fetchForecast)
    }

    @Test
    fun `nothing to draw or never fetched here always fetches`() {
        assertTrue(decide(60_000L, drawable = false).fetchForecast)
        assertTrue(decide(null).fetchForecast)
    }

    @Test
    fun `the limit is the source toggle's`() {
        assertEquals(SourceToggleRefreshPolicy.staleMs(true), LocationChangeRefreshPolicy.staleAfterMs(true))
        assertEquals(SourceToggleRefreshPolicy.staleMs(false), LocationChangeRefreshPolicy.staleAfterMs(false))
    }
}
