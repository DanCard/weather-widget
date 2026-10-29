package com.weatherwidget.shared.contract

import com.weatherwidget.shared.util.ChargeHistory
import com.weatherwidget.shared.util.PreUnplugFetchRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [PreUnplugFetchRunner.Host] stores, on Android's prefs Host and the desktop daemon's file Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md; plans/261028-learned-unplug-prediction.md):
 * an empty history at first, and a history that outlives the process — the predictor learns over weeks.
 */
abstract class PreUnplugFetchHostContract {
    /** A Host on fresh, empty storage whose clock reads [nowMs]. */
    protected abstract fun open(nowMs: Long): PreUnplugFetchRunner.Host

    /** A new Host on the storage of the last [open] — a process restart. */
    protected abstract fun reopen(nowMs: Long): PreUnplugFetchRunner.Host

    @Test
    fun `fresh storage is an empty history`() {
        assertEquals(ChargeHistory.State(), open(T0).store.load())
    }

    @Test
    fun `a saved history survives a restart`() {
        val state = ChargeHistory.State(
            sessions = listOf(ChargeHistory.Session(T0 - 10 * HOUR, T0 - HOUR, exact = true)),
            alarms = listOf(T0 - 2 * HOUR),
            pendingAlarmMs = T0 + 9 * HOUR,
            trackedWakeAtMs = T0 + 8 * HOUR,
            trackedSource = "weekly(tue)",
        )
        open(T0).store.save(state)
        assertEquals(state, reopen(T0).store.load())
    }

    @Test
    fun `a charge observed through the runner is a stored session after a restart`() = runBlocking {
        PreUnplugFetchRunner(open(T0)).observePower(isCharging = true)
        PreUnplugFetchRunner(reopen(T0 + HOUR)).observePower(isCharging = false, exact = true)
        assertEquals(listOf(ChargeHistory.Session(T0, T0 + HOUR, exact = true)), reopen(T0 + HOUR).store.load().sessions)
    }

    companion object {
        const val HOUR = 3_600_000L
        const val T0 = 1_792_900_800_000L
    }
}
