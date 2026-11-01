package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SourceToggleRefreshPolicyTest {
    private val hour = 3_600_000L

    @Test
    fun `on a charger a toggle refreshes past the two hour charging interval`() {
        assertEquals(2 * hour, SourceToggleRefreshPolicy.staleMs(isCharging = true))
    }

    @Test
    fun `off a charger a toggle refreshes past four hours`() {
        assertEquals(4 * hour, SourceToggleRefreshPolicy.staleMs(isCharging = false))
    }
}
