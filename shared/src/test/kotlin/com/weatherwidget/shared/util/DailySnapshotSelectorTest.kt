package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailySnapshotSelectorTest {

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L
    private val cutoff = now - DailySnapshotSelector.PRIOR_WINDOW_HOURS * hour

    private fun select(vararg fetchedAt: Long): Long? =
        DailySnapshotSelector.selectPriorDaySnapshot(fetchedAt.toList(), now) { it }

    @Test
    fun prefersMostRecentSnapshotOlderThan24h() {
        // 30h ago and 26h ago are both past the cutoff; pick the more recent (26h).
        val result = select(now - 30 * hour, now - 26 * hour, now - 2 * hour)
        assertEquals(now - 26 * hour, result)
    }

    @Test
    fun fallsBackToEarliestWhenNoneOlderThan24h() {
        // All within 24h — fall back to the earliest available.
        val result = select(now - 2 * hour, now - 10 * hour, now - 5 * hour)
        assertEquals(now - 10 * hour, result)
    }

    @Test
    fun boundaryFetchedExactlyAt24hIsNotOldEnough() {
        // cutoff is strict (< cutoff); a candidate exactly at the cutoff is treated as "fresh".
        val result = select(cutoff, now - 1 * hour)
        // Neither is older than cutoff, so earliest (cutoff) wins via fallback.
        assertEquals(cutoff, result)
    }

    @Test
    fun emptyReturnsNull() {
        assertNull(select())
    }

    @Test
    fun singleCandidateReturnedRegardlessOfAge() {
        assertEquals(now - 2 * hour, select(now - 2 * hour))
        assertEquals(now - 48 * hour, select(now - 48 * hour))
    }
}
