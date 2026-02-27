package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ForecastStalenessPolicyTest {

    @Test
    fun `primary API has 60 minute staleness threshold`() {
        assertEquals(60 * 60 * 1000L, ForecastStalenessPolicy.getStalenessThresholdMs(0))
    }

    @Test
    fun `secondary API has 90 minute staleness threshold`() {
        assertEquals(90 * 60 * 1000L, ForecastStalenessPolicy.getStalenessThresholdMs(1))
    }

    @Test
    fun `third API has 120 minute staleness threshold`() {
        assertEquals(120 * 60 * 1000L, ForecastStalenessPolicy.getStalenessThresholdMs(2))
    }

    @Test
    fun `fourth API uses same threshold as third`() {
        assertEquals(120 * 60 * 1000L, ForecastStalenessPolicy.getStalenessThresholdMs(3))
    }
}
