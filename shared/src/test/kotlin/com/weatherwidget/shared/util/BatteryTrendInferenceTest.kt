package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Covers [BatteryTier.inferChargingFromLevelTrend], the fallback used where the platform charging
 * signal lies (Samsung "Protect battery" reports `plug=none status=discharging` at its cap).
 */
@Category(ShortDuration::class)
class BatteryTrendInferenceTest {

    private fun infer(previous: Int, current: Int, previousInference: Boolean = false) =
        BatteryTier.inferChargingFromLevelTrend(previous, current, previousInference)

    @Test
    fun `a rising level proves charging`() {
        assertTrue(infer(previous = 78, current = 80))
    }

    @Test
    fun `a falling level proves discharging even after charging was proven`() {
        assertFalse(infer(previous = 80, current = 79, previousInference = true))
    }

    @Test
    fun `a plateau holds the last proven verdict`() {
        assertTrue(infer(previous = 80, current = 80, previousInference = true))
        assertFalse(infer(previous = 80, current = 80, previousInference = false))
    }

    @Test
    fun `no inference below the held-charge threshold even while rising`() {
        assertFalse(infer(previous = 76, current = 77))
    }

    @Test
    fun `the threshold boundary itself is eligible`() {
        assertTrue(infer(previous = 77, current = BatteryTier.HELD_CHARGE_MIN_LEVEL))
    }

    @Test
    fun `a high level with no recorded history counts as charging`() {
        // The cap case on a cold start: a battery pinned at its charge limit never rises, so
        // demanding a rise as proof would leave the verdict stuck at "not charging" for as long as
        // the phone stayed on the charger.
        assertTrue(infer(previous = -1, current = 85))
    }

    @Test
    fun `a low level with no recorded history is not charging`() {
        assertFalse(infer(previous = -1, current = 40))
    }

    @Test
    fun `a battery held at the cap from a cold start stays charging`() {
        var inference = infer(previous = -1, current = 80)
        var previous = 80
        repeat(50) {
            inference = infer(previous, 80, inference)
            previous = 80
        }
        assertTrue("a level pinned at the cap must read as charging", inference)
    }

    @Test
    fun `an unplugged phone above the threshold self-corrects at its first drop`() {
        // The accepted cost of treating a plateau as charging: wrong until the first lost point.
        var inference = infer(previous = -1, current = 85)
        assertTrue("starts optimistic", inference)

        inference = infer(previous = 85, current = 85, previousInference = inference)
        assertTrue("still optimistic while held", inference)

        inference = infer(previous = 85, current = 84, previousInference = inference)
        assertFalse("the first drop must latch discharging", inference)

        inference = infer(previous = 84, current = 84, previousInference = inference)
        assertFalse("and it must not drift back on the next plateau", inference)
    }

    /**
     * The case the stickiness exists for. A non-sticky "not dropping means charging" rule flips to
     * true on every plateau between drops, flapping the fetch cadence the whole way down.
     */
    @Test
    fun `a draining battery never flips back to charging on its plateaus`() {
        var inference = true
        val drain = listOf(80, 79, 79, 79, 78, 78)
        var previous = 80
        val verdicts = mutableListOf<Boolean>()
        for (level in drain) {
            inference = infer(previous, level, inference)
            verdicts += inference
            previous = level
        }
        assertTrue("first sample is a plateau and holds the prior verdict", verdicts.first())
        assertFalse("the 80->79 drop latches discharging", verdicts[1])
        assertTrue(
            "every later sample must stay discharging, got $verdicts",
            verdicts.drop(1).none { it },
        )
    }

    /**
     * The Samsung cap case end to end: climb to the cap, then hold there indefinitely.
     */
    @Test
    fun `a charge held at the cap stays charging across many plateau reads`() {
        var inference = false
        var previous = -1
        for (level in listOf(76, 78, 80)) {
            inference = infer(previous, level, inference)
            previous = level
        }
        assertTrue("the climb to the cap should prove charging", inference)

        repeat(50) {
            inference = infer(80, 80, inference)
        }
        assertTrue("holding at the cap must not decay to discharging", inference)
    }
}
