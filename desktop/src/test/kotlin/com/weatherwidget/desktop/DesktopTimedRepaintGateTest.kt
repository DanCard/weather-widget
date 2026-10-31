package com.weatherwidget.desktop

import com.weatherwidget.shared.util.TimedRepaintGate
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopTimedRepaintGateTest {
    private var now = 1_000_000L
    private val gate = TimedRepaintGate { now }

    @Test
    fun `an idle fallback tick reloads`() = runBlocking {
        assertTrue(fallbackReloadAdmitted(resumed = false, gate))
    }

    @Test
    fun `a fallback tick right after a click waits for the next tick`() = runBlocking {
        gate.interactionObserved()
        assertFalse(fallbackReloadAdmitted(resumed = false, gate))
        now += TimedRepaintGate.QUIET_MS
        assertTrue(fallbackReloadAdmitted(resumed = false, gate))
    }

    @Test
    fun `a resume reloads even right after a click`() = runBlocking {
        gate.interactionObserved()
        assertTrue(fallbackReloadAdmitted(resumed = true, gate))
    }
}
