package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import kotlin.random.Random

@Category(ShortDuration::class)
class StartupFetchPolicyTest {

    private val zeroJitter = Random(0)

    @Test
    fun `null age counts as very stale`() {
        assertTrue(StartupFetchPolicy.isVeryStale(null))
    }

    @Test
    fun `age at threshold counts as very stale`() {
        assertTrue(StartupFetchPolicy.isVeryStale(StartupFetchPolicy.VERY_STALE_THRESHOLD_MINUTES))
    }

    @Test
    fun `age just under threshold is not very stale`() {
        assertFalse(StartupFetchPolicy.isVeryStale(StartupFetchPolicy.VERY_STALE_THRESHOLD_MINUTES - 1))
    }

    @Test
    fun `primary delay for normal staleness falls in the normal window`() {
        repeat(50) { seed ->
            val delay = StartupFetchPolicy.primaryFetchDelayMs(dataAgeMinutes = 30L, random = Random(seed))
            assertTrue(
                "delay=$delay out of [${StartupFetchPolicy.NORMAL_DELAY_MIN_MS}, ${StartupFetchPolicy.NORMAL_DELAY_MAX_MS}]",
                delay in StartupFetchPolicy.NORMAL_DELAY_MIN_MS..StartupFetchPolicy.NORMAL_DELAY_MAX_MS,
            )
        }
    }

    @Test
    fun `primary delay for very stale data falls in the fast-lane window`() {
        repeat(50) { seed ->
            val delay = StartupFetchPolicy.primaryFetchDelayMs(dataAgeMinutes = null, random = Random(seed))
            assertTrue(
                "delay=$delay out of [${StartupFetchPolicy.VERY_STALE_DELAY_MIN_MS}, ${StartupFetchPolicy.VERY_STALE_DELAY_MAX_MS}]",
                delay in StartupFetchPolicy.VERY_STALE_DELAY_MIN_MS..StartupFetchPolicy.VERY_STALE_DELAY_MAX_MS,
            )
        }
    }

    @Test
    fun `primary delay for a very old but non-null age uses the fast lane`() {
        val delay = StartupFetchPolicy.primaryFetchDelayMs(dataAgeMinutes = 24 * 60L, random = zeroJitter)
        assertTrue(delay in StartupFetchPolicy.VERY_STALE_DELAY_MIN_MS..StartupFetchPolicy.VERY_STALE_DELAY_MAX_MS)
    }

    @Test
    fun `history repair delay falls in its own short window`() {
        repeat(50) { seed ->
            val delay = StartupFetchPolicy.historyRepairDelayMs(random = Random(seed))
            assertTrue(
                "delay=$delay out of [${StartupFetchPolicy.HISTORY_REPAIR_DELAY_MIN_MS}, ${StartupFetchPolicy.HISTORY_REPAIR_DELAY_MAX_MS}]",
                delay in StartupFetchPolicy.HISTORY_REPAIR_DELAY_MIN_MS..StartupFetchPolicy.HISTORY_REPAIR_DELAY_MAX_MS,
            )
        }
    }

    @Test
    fun `repeated calls are jittered, not identical`() {
        val delays = (0 until 20).map { seed -> StartupFetchPolicy.primaryFetchDelayMs(dataAgeMinutes = 30L, random = Random(seed)) }
        assertTrue("expected jitter to produce varied delays, got all-identical: ${delays.first()}", delays.toSet().size > 1)
    }
}
