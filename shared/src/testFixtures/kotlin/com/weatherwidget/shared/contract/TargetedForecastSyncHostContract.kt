package com.weatherwidget.shared.contract

import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.shared.util.TargetedForecastSyncRun.Enqueue
import com.weatherwidget.shared.util.TargetedForecastSyncRun.TailState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What [TargetedForecastSyncRun.Host]'s tail timer means on Android (WorkManager unique work) and desktop
 * (`DesktopPostSyncTail`) (plans/261123-shared-targeted-sync-paint-then-tail.md): one tail waits at a
 * time, KEEP_PENDING arms nothing new, and nothing is ever replaced. The observation and actuals I/O
 * is covered by each platform's integration test, not here.
 */
abstract class TargetedForecastSyncHostContract {
    /** A host with no tail armed. */
    protected abstract fun open(): TargetedForecastSyncRun.Host

    /** How many tails are waiting or running now. */
    protected abstract fun armedTails(): Int

    /** Drop whatever the test armed. */
    protected abstract fun close()

    private lateinit var host: TargetedForecastSyncRun.Host

    @Before
    fun openHost() {
        host = open()
    }

    @After
    fun closeHost() = close()

    @Test
    fun `nothing armed reads as no tail`() = runBlocking {
        assertEquals(TailState.NONE, host.tailState())
        assertEquals(0, armedTails())
    }

    @Test
    fun `SCHEDULE arms one waiting tail`() = runBlocking {
        host.scheduleTail(Enqueue.SCHEDULE, LONG_DELAY_MS, setOf("NWS"))
        assertEquals(TailState.PENDING, host.tailState())
        assertEquals(1, armedTails())
    }

    @Test
    fun `KEEP_PENDING and a second SCHEDULE leave the one waiting tail alone`() = runBlocking {
        host.scheduleTail(Enqueue.SCHEDULE, LONG_DELAY_MS, setOf("NWS"))
        host.scheduleTail(Enqueue.KEEP_PENDING, LONG_DELAY_MS, setOf("OPEN_METEO"))
        host.scheduleTail(Enqueue.SCHEDULE, LONG_DELAY_MS, setOf("SILURIAN"))
        assertEquals(TailState.PENDING, host.tailState())
        assertEquals(1, armedTails())
    }

    @Test
    fun `KEEP_PENDING with nothing armed arms nothing`() = runBlocking {
        host.scheduleTail(Enqueue.KEEP_PENDING, LONG_DELAY_MS, setOf("NWS"))
        assertEquals(TailState.NONE, host.tailState())
        assertEquals(0, armedTails())
    }

    @Test
    fun `APPEND_AFTER_RUNNING with nothing running arms one tail, and a second does not stack`() = runBlocking {
        host.scheduleTail(Enqueue.APPEND_AFTER_RUNNING, LONG_DELAY_MS, setOf("NWS"))
        host.scheduleTail(Enqueue.APPEND_AFTER_RUNNING, LONG_DELAY_MS, setOf("NWS"))
        assertEquals(TailState.PENDING, host.tailState())
        assertEquals(1, armedTails())
    }

    companion object {
        /** Long enough that no test sees the tail come due. */
        const val LONG_DELAY_MS = 3_600_000L
    }
}
