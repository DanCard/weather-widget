package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.TimedRepaintGate.Companion.MAX_DEFER_MS
import com.weatherwidget.shared.util.TimedRepaintGate.Companion.QUIET_MS
import com.weatherwidget.shared.util.TimedRepaintGate.Companion.TAG_DEFERRED
import com.weatherwidget.shared.util.TimedRepaintGate.Companion.TAG_FORCED
import com.weatherwidget.shared.util.TimedRepaintGate.Trigger
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TimedRepaintGateTest {
    private var now = 1_000_000L
    private val tags = mutableListOf<String>()
    private val gate = TimedRepaintGate { now }

    private fun admit(trigger: Trigger = Trigger.UI_TICK) = runBlocking {
        gate.admit(trigger) { tag, _ -> tags += tag }
    }

    @Test
    fun `runs when nothing has happened`() {
        assertTrue(admit())
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `defers while an interaction is active however old it is`() {
        gate.interactionStarted()
        now += QUIET_MS * 2
        assertFalse(admit())
        assertEquals(listOf(TAG_DEFERRED), tags)
    }

    @Test
    fun `defers inside the quiet window and runs at its end`() {
        gate.interactionStarted()
        gate.interactionEnded()
        now += QUIET_MS - 1
        assertFalse(admit())
        now += 1
        assertTrue(admit())
    }

    @Test
    fun `a window-only observation counts as an interaction`() {
        gate.interactionObserved()
        assertFalse(admit())
    }

    @Test
    fun `overlapping interactions stay active until the last one ends`() {
        gate.interactionStarted()
        gate.interactionStarted()
        gate.interactionEnded()
        now += QUIET_MS
        assertFalse(admit())
        gate.interactionEnded()
        now += QUIET_MS
        assertTrue(admit())
    }

    @Test
    fun `a stray end cannot make a later interaction look finished`() {
        gate.interactionEnded()
        gate.interactionStarted()
        now += QUIET_MS
        assertFalse(admit())
    }

    @Test
    fun `a repaint refused for the full cap runs anyway`() {
        gate.interactionStarted()
        assertFalse(admit())
        now += MAX_DEFER_MS - 1
        assertFalse(admit())
        now += 1
        assertTrue(admit())
        assertEquals(TAG_FORCED, tags.last())
    }

    @Test
    fun `the cap restarts after a repaint runs`() {
        gate.interactionStarted()
        admit()
        now += MAX_DEFER_MS
        assertTrue(admit())
        assertFalse(admit())
    }

    @Test
    fun `triggers are capped independently`() {
        gate.interactionStarted()
        admit(Trigger.UI_TICK)
        now += MAX_DEFER_MS
        assertFalse(admit(Trigger.NOTICE_EXPIRY))
        assertTrue(admit(Trigger.UI_TICK))
    }

    @Test
    fun `a clock that stepped backwards does not defer`() {
        gate.interactionObserved()
        now -= 60_000L
        assertTrue(admit())
    }
}
