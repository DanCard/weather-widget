package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class BackfillDeferralPolicyTest {
    @Test
    fun `defers 20s on each interaction up to 90s cap`() {
        var now = 1_000L
        val policy = BackfillDeferralPolicy { now }

        assertEquals(BackfillDeferralPolicy.DEFER_DELAY_MS, policy.deferOnInteraction())
        now += 40_000L
        assertEquals(BackfillDeferralPolicy.DEFER_DELAY_MS, policy.deferOnInteraction())
        now += 50_000L // 90 s total elapsed -> capped
        assertNull(policy.deferOnInteraction())
    }

    @Test
    fun `yields at start while a user forecast sync or recent tap is active, then runs once idle or capped`() {
        var now = 10_000L
        val policy = BackfillDeferralPolicy { now }

        // Idle -> proceed immediately
        assertNull(policy.yieldAtStart(activeInteractions = 0, lastInteractionMs = null))

        // Active user sync -> yield 20s
        policy.userSyncStarted()
        assertEquals(BackfillDeferralPolicy.DEFER_DELAY_MS, policy.yieldAtStart(activeInteractions = 0, lastInteractionMs = null))

        // User sync finished, recent tap 2s ago -> still yields
        policy.userSyncEnded()
        assertEquals(BackfillDeferralPolicy.DEFER_DELAY_MS, policy.yieldAtStart(activeInteractions = 0, lastInteractionMs = now - 2_000L))

        // Cap reached -> proceeds even if busy
        now += BackfillDeferralPolicy.MAX_TOTAL_DEFER_MS
        assertNull(policy.yieldAtStart(activeInteractions = 1, lastInteractionMs = now))
    }
}
