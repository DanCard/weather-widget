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
}
