package com.weatherwidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerConnectedRefreshPolicyTest {

    @Test
    fun `should enqueue when there is no prior refresh`() {
        val now = PowerConnectedRefreshPolicy.DEBOUNCE_MS + 1_000L
        assertTrue(
            PowerConnectedRefreshPolicy.shouldEnqueueRefresh(
                nowMs = now,
                lastRefreshMs = 0L,
            ),
        )
    }

    @Test
    fun `should not enqueue within debounce window`() {
        assertFalse(
            PowerConnectedRefreshPolicy.shouldEnqueueRefresh(
                nowMs = 100_000L,
                lastRefreshMs = 100_000L - PowerConnectedRefreshPolicy.DEBOUNCE_MS + 1L,
            ),
        )
    }

    @Test
    fun `should enqueue at debounce boundary and beyond`() {
        val now = 500_000L
        val boundaryLastRefresh = now - PowerConnectedRefreshPolicy.DEBOUNCE_MS

        assertTrue(
            PowerConnectedRefreshPolicy.shouldEnqueueRefresh(
                nowMs = now,
                lastRefreshMs = boundaryLastRefresh,
            ),
        )
        assertTrue(
            PowerConnectedRefreshPolicy.shouldEnqueueRefresh(
                nowMs = now,
                lastRefreshMs = now - PowerConnectedRefreshPolicy.DEBOUNCE_MS - 1L,
            ),
        )
    }
}
