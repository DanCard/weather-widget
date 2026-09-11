package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForcedRefreshSatisfactionTest {
    /** The 18:03:35 toggle sync: fetched at 18:03:46, killed at 18:03:50, re-run at 18:04:05. */
    @Test
    fun `a fetch that succeeded after the request satisfies it`() {
        assertTrue(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 1_000, lastSuccessMs = 11_000))
    }

    @Test
    fun `a fetch from before the request does not`() {
        assertFalse(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 20_000, lastSuccessMs = 11_000))
        assertFalse(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 11_000, lastSuccessMs = 11_000))
    }

    /** Enqueue paths that do not stamp a request time keep their force. */
    @Test
    fun `an unstamped request is never satisfied`() {
        assertFalse(ForcedRefreshSatisfaction.isSatisfied(requestedAtMs = 0, lastSuccessMs = 11_000))
    }
}
