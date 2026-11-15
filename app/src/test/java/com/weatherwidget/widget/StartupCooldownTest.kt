package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The post-start quiet period, on a fake clock. Process start is t=100_000 so the arithmetic is
 * legible; cooldown 30 s, interaction quiet 10 s.
 */
@Category(ShortDuration::class)
class StartupCooldownTest {
    private val start = 100_000L
    private fun cooldown() = StartupCooldown(processStartElapsedMs = start, cooldownMs = 30_000L, interactionQuietMs = 10_000L)

    /** The 2026-08-19 trap: WorkManager cold-starts the process to run the job; nobody is watching. */
    @Test
    fun `no cooldown at all until a user-facing trigger has been seen`() {
        val c = cooldown()
        assertEquals(0L, c.remainingMs(start + 100))
        assertEquals(0L, c.remainingMs(start + 15_000))
    }

    @Test
    fun `a user-facing start defers background work until the fixed cooldown lapses`() {
        val c = cooldown()
        c.onUserFacingTrigger(start + 600) // onUpdate at 0.6 s
        assertEquals(29_400L, c.remainingMs(start + 600))
        assertEquals(10_000L, c.remainingMs(start + 20_000))
        assertEquals(0L, c.remainingMs(start + 30_000))
    }

    @Test
    fun `interactions inside the cooldown extend it to ten seconds of quiet`() {
        val c = cooldown()
        c.onUserFacingTrigger(start + 600)
        c.onUserFacingTrigger(start + 25_000) // tap at 25 s -> ends no sooner than 35 s
        assertEquals(start + 35_000, c.endsAtElapsedMs())
        c.onUserFacingTrigger(start + 34_000) // still inside -> 44 s
        assertEquals(start + 44_000, c.endsAtElapsedMs())
        assertEquals(4_000L, c.remainingMs(start + 40_000))
        assertEquals(0L, c.remainingMs(start + 44_000))
    }

    @Test
    fun `an early interaction never shortens the fixed part`() {
        val c = cooldown()
        c.onUserFacingTrigger(start + 5_000) // 5 s + 10 s quiet = 15 s < 30 s fixed
        assertEquals(start + 30_000, c.endsAtElapsedMs())
    }

    @Test
    fun `a tap after the cooldown has lapsed does not restart it`() {
        val c = cooldown()
        c.onUserFacingTrigger(start + 600)
        c.onUserFacingTrigger(start + 50_000)
        assertEquals(0L, c.remainingMs(start + 50_000))
        assertEquals(0L, c.remainingMs(start + 51_000))
    }

    /** Worker started the process; the user then taps at 2 s. The user is present now, so the cooldown applies. */
    @Test
    fun `a job-started process gains the cooldown when the user shows up inside it`() {
        val c = cooldown()
        assertEquals(0L, c.remainingMs(start + 1_000))
        c.onUserFacingTrigger(start + 2_000)
        assertEquals(28_000L, c.remainingMs(start + 2_000))
    }

    /** Boot, 2026-11-15: the clock lapsed at 6 s with the paint still loading; three syncs started beside it. */
    @Test
    fun `a pending startup paint holds background work past the clock`() {
        val c = cooldown()
        c.onUserFacingTrigger(start + 600)
        c.onStartupPaintPending(start + 20_000) // cap 30 s -> 50 s; the clock ends at 30 s
        assertEquals(3_000L, c.remainingMs(start + 45_000))
        assertEquals(StartupCooldown.CAUSE_PAINT_PENDING, c.deferCause(start + 45_000))
    }

    @Test
    fun `the hold ends when the paint settles`() {
        val c = cooldown()
        c.onUserFacingTrigger(start + 600)
        c.onStartupPaintPending(start + 1_000)
        c.onStartupPaintSettled()
        assertEquals(0L, c.remainingMs(start + 45_000))
        // The clock still applies after the paint: taps follow it.
        assertEquals(10_000L, c.remainingMs(start + 20_000))
        assertEquals(StartupCooldown.CAUSE_COOLDOWN, c.deferCause(start + 20_000))
    }

    @Test
    fun `a paint that never settles stops holding at the cap`() {
        val c = cooldown()
        c.onUserFacingTrigger(start + 600)
        c.onStartupPaintPending(start + 40_000) // cap 30 s -> 70 s
        assertEquals(3_000L, c.remainingMs(start + 60_000))
        assertEquals(1_000L, c.remainingMs(start + 69_000))
        assertEquals(0L, c.remainingMs(start + 70_000))
    }

    @Test
    fun `overlapping paints hold until the last settles and the cap counts from the first`() {
        val c = cooldown()
        c.onStartupPaintPending(start + 40_000)
        c.onStartupPaintPending(start + 50_000)
        c.onStartupPaintSettled()
        assertEquals(3_000L, c.remainingMs(start + 60_000))
        assertEquals(0L, c.remainingMs(start + 70_000))
        c.onStartupPaintSettled()
        assertEquals(0L, c.remainingMs(start + 60_000))
    }

    /** A pending startup paint is itself user-facing; it needs no separate trigger. */
    @Test
    fun `a pending paint holds even before a trigger was recorded`() {
        val c = cooldown()
        c.onStartupPaintPending(start + 100)
        assertEquals(3_000L, c.remainingMs(start + 200))
    }

    @Test
    fun `paint hold defaults are a thirty second cap in three second steps`() {
        assertEquals(30_000L, StartupCooldown.PAINT_HOLD_CAP_MS)
        assertEquals(3_000L, StartupCooldown.PAINT_RECHECK_MS)
    }

    @Test
    fun `default constants are six seconds`() {
        assertEquals(6_000L, StartupCooldown.COOLDOWN_MS)
        assertEquals(6_000L, StartupCooldown.INTERACTION_QUIET_MS)
    }
}
