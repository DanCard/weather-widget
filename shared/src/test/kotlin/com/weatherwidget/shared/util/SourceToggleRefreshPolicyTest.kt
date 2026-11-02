package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SourceToggleRefreshPolicyTest {
    private val hour = 3_600_000L

    @Test
    fun `at high battery or on a charger a toggle refreshes past two hours`() {
        assertEquals(2 * hour, SourceToggleRefreshPolicy.staleMs(isHighBattery = true))
    }

    @Test
    fun `at low battery a toggle refreshes past four hours`() {
        assertEquals(4 * hour, SourceToggleRefreshPolicy.staleMs(isHighBattery = false))
    }
}
