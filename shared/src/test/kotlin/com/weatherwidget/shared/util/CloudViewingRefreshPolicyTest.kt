package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CloudViewingRefreshPolicyTest {

    private val threshold = CloudViewingRefreshPolicy.CLOUD_STALE_WHILE_VIEWING_MS

    @Test
    fun `absent data is not stale`() {
        assertFalse(CloudViewingRefreshPolicy.isStale(null, 1_000_000L))
    }

    @Test
    fun `exactly at the threshold is not yet stale`() {
        val now = 10_000_000L
        assertFalse(CloudViewingRefreshPolicy.isStale(now - threshold, now))
    }

    @Test
    fun `just inside the threshold is fresh`() {
        val now = 10_000_000L
        assertFalse(CloudViewingRefreshPolicy.isStale(now - threshold + 1, now))
    }

    @Test
    fun `just past the threshold is stale`() {
        val now = 10_000_000L
        assertTrue(CloudViewingRefreshPolicy.isStale(now - threshold - 1, now))
    }
}
