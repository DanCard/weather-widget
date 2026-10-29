package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** plans/261028-learned-unplug-prediction.md */
@Category(ShortDuration::class)
class ChargeHistoryTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_800_000_000_000L

    private fun charging(state: ChargeHistory.State, at: Long) = ChargeHistory.observe(state, true, at, exact = false).state

    @Test
    fun `an unplug seen later is recorded at the last charging sighting`() {
        var s = charging(ChargeHistory.State(), t0)
        s = charging(s, t0 + 2 * hour)
        val o = ChargeHistory.observe(s, false, t0 + 2 * hour + 20 * minute, exact = false)
        assertEquals(ChargeHistory.Session(t0, t0 + 2 * hour, exact = false), o.state.sessions.single())
        assertEquals(t0 + 2 * hour, o.unplugAtMs)
        assertNull(o.state.chargingSinceMs)
    }

    @Test
    fun `the power event records the exact time, daytime top-ups included`() {
        val s = charging(ChargeHistory.State(), t0)
        val o = ChargeHistory.observe(s, false, t0 + 40 * minute, exact = true)
        assertEquals(ChargeHistory.Session(t0, t0 + 40 * minute, exact = true), o.state.sessions.single())
        assertTrue(o.logLine!!.startsWith("action=recorded"))
    }

    @Test
    fun `a brief plug-in and an unbounded gap are not recorded`() {
        val brief = ChargeHistory.observe(charging(ChargeHistory.State(), t0), false, t0 + 5 * minute, exact = true)
        assertTrue(brief.state.sessions.isEmpty())
        assertTrue(brief.logLine!!.contains("reason=brief"))
        val gap = ChargeHistory.observe(charging(ChargeHistory.State(), t0), false, t0 + 3 * hour, exact = false)
        assertTrue(gap.state.sessions.isEmpty())
        assertTrue(gap.logLine!!.contains("reason=unbounded"))
    }

    @Test
    fun `sessions older than the retention are dropped`() {
        val old = ChargeHistory.Session(t0, t0 + hour, true)
        val s = charging(ChargeHistory.State(sessions = listOf(old)), t0 + 60L * 24 * hour)
        val o = ChargeHistory.observe(s, false, t0 + 60L * 24 * hour + hour, exact = true)
        assertEquals(1, o.state.sessions.size)
        assertFalse(old in o.state.sessions)
    }

    @Test
    fun `a passed alarm is recorded once and the next one remembered`() {
        var s = ChargeHistory.noteAlarm(ChargeHistory.State(), t0 + hour, t0)
        assertEquals(t0 + hour, s.pendingAlarmMs)
        s = ChargeHistory.noteAlarm(s, t0 + 25 * hour, t0 + hour + minute)
        assertEquals(listOf(t0 + hour), s.alarms)
        assertEquals(t0 + 25 * hour, s.pendingAlarmMs)
        s = ChargeHistory.noteAlarm(s, t0 + 25 * hour, t0 + 2 * hour)
        assertEquals(listOf(t0 + hour), s.alarms)
    }

    @Test
    fun `charging-at covers closed and open sessions`() {
        val s = ChargeHistory.State(
            sessions = listOf(ChargeHistory.Session(t0, t0 + hour, true)),
            chargingSinceMs = t0 + 3 * hour,
            lastSeenChargingMs = t0 + 4 * hour,
        )
        assertTrue(s.chargingAt(t0 + 30 * minute))
        assertFalse(s.chargingAt(t0 + 2 * hour))
        assertTrue(s.chargingAt(t0 + 3 * hour + 30 * minute))
        assertFalse("past the last sighting is unknown", s.chargingAt(t0 + 5 * hour))
    }

    @Test
    fun `state round-trips through its encoding`() {
        val s = ChargeHistory.State(
            chargingSinceMs = t0,
            lastSeenChargingMs = t0 + minute,
            sessions = listOf(ChargeHistory.Session(t0 - 9 * hour, t0 - hour, true), ChargeHistory.Session(t0 - 30 * hour, t0 - 20 * hour, false)),
            alarms = listOf(t0 - 5 * hour),
            pendingAlarmMs = t0 + 8 * hour,
            armedWakeAtMs = t0 + 7 * hour,
            armedSource = "weekly(tue)",
            trackedWakeAtMs = t0 + 7 * hour,
            trackedSource = "weekly(tue)",
            patternLogDate = "2026-10-28",
        )
        assertEquals(s, ChargeHistory.State.decode(s.encode()))
        assertEquals(ChargeHistory.State(), ChargeHistory.State.decode(ChargeHistory.State().encode()))
        assertNull(ChargeHistory.State.decode("garbage"))
    }
}
