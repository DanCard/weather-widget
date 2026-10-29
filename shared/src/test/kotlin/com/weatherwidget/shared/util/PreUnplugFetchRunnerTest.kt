package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The runner with [ChargeHistory] and [UnplugPredictor] behind a fake host — the same path Android's
 * prefs/WorkManager host and the desktop daemon's file/loop host take. plans/261028-learned-unplug-prediction.md
 */
@Category(ShortDuration::class)
class PreUnplugFetchRunnerTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val minute = 60_000L
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    private val monday = LocalDate.of(2026, 11, 2)
    private val tuesday = monday.plusDays(1)

    private class FakeHost(var now: Long, val zoneId: ZoneId) : PreUnplugFetchRunner.Host {
        var saved: ChargeHistory.State = ChargeHistory.State()
        var alarm: Long? = null
        val checks = mutableListOf<Long>()
        var cancels = 0
        val logs = mutableListOf<Pair<String, String>>()
        override val store = object : ChargeHistoryStore {
            override fun load() = saved
            override fun save(state: ChargeHistory.State) { saved = state }
        }
        override fun nowMs() = now
        override fun zone() = zoneId
        override fun nextAlarmMs() = alarm
        override fun scheduleCheck(delayMs: Long) { checks += delayMs }
        override fun cancelCheck() { cancels++ }
        override suspend fun log(tag: String, message: String, level: String) { logs += tag to message }
        fun lines(tag: String) = logs.filter { it.first == tag }.map { it.second }
    }

    private fun basketballHistory() = ChargeHistory.State(
        sessions = listOf(2L, 1L).map { weeks ->
            val day = tuesday.minusWeeks(weeks)
            ChargeHistory.Session(ms(day.minusDays(1).atTime(20, 0)), ms(day.atTime(11, if (weeks == 2L) 50 else 40)), true)
        },
        chargingSinceMs = ms(monday.atTime(20, 0)),
        lastSeenChargingMs = ms(monday.atTime(21, 55)),
    )

    @Test
    fun `a Tuesday habit arms one check inside the lead before 11-40`() = runBlocking {
        val host = FakeHost(ms(monday.atTime(22, 0)), zone)
        host.saved = basketballHistory()
        val runner = PreUnplugFetchRunner(host)
        runner.arm("sync")
        val checkAt = ms(tuesday.atTime(11, 40)) - ForecastHorizonCadence.PRE_WAKE_LEAD_MS + ForecastHorizonCadence.PRE_WAKE_CHECK_SLACK_MS
        assertEquals(listOf(checkAt - host.now), host.checks)
        assertEquals(ms(tuesday.atTime(11, 40)), host.saved.armedWakeAtMs)
        assertTrue(host.lines(PreUnplugFetchRunner.FETCH_TAG).single().contains("source=weekly(tue)"))
        runner.arm("sync")
        assertEquals("unchanged prediction is not re-armed", 1, host.checks.size)
        assertEquals(ms(tuesday.atTime(11, 40)), runner.nextWake()!!.atMs)
    }

    @Test
    fun `a bare 22-00 alarm arms nothing`() = runBlocking {
        val host = FakeHost(ms(monday.atTime(20, 0)), zone)
        host.alarm = ms(monday.atTime(22, 0))
        PreUnplugFetchRunner(host).arm("alarm_changed")
        assertTrue(host.checks.isEmpty())
        assertNull(host.saved.armedWakeAtMs)
        assertEquals("the alarm is remembered to be judged later", host.alarm, host.saved.pendingAlarmMs)
    }

    @Test
    fun `an unplug near the armed prediction logs a hit`() = runBlocking {
        val host = FakeHost(ms(monday.atTime(22, 0)), zone)
        host.saved = basketballHistory()
        val runner = PreUnplugFetchRunner(host)
        runner.arm("sync")
        host.now = ms(tuesday.atTime(11, 30))
        runner.observePower(isCharging = true)
        host.now = ms(tuesday.atTime(11, 44))
        runner.observePower(isCharging = false, exact = true)
        val outcome = host.lines(PreUnplugFetchRunner.OUTCOME_TAG).single()
        assertTrue(outcome, outcome.startsWith("outcome=hit") && outcome.contains("deltaMin=4") && outcome.contains("source=weekly(tue)"))
        assertNull("judged once", host.saved.trackedWakeAtMs)
    }

    @Test
    fun `staying on the charger past the window logs a miss`() = runBlocking {
        val host = FakeHost(ms(monday.atTime(22, 0)), zone)
        host.saved = basketballHistory()
        val runner = PreUnplugFetchRunner(host)
        runner.arm("sync")
        for (h in 10..14) {
            host.now = ms(tuesday.atTime(h, 0))
            runner.observePower(isCharging = true)
            runner.arm("tick")
        }
        assertEquals(listOf("outcome=miss source=weekly(tue) predicted=${tuesday} 11:40"), host.lines(PreUnplugFetchRunner.OUTCOME_TAG))
    }

    @Test
    fun `the daily pattern line is logged once a day`() = runBlocking {
        val host = FakeHost(ms(monday.atTime(22, 0)), zone)
        host.saved = basketballHistory()
        val runner = PreUnplugFetchRunner(host)
        runner.dailyPatternLog()
        runner.dailyPatternLog()
        val lines = host.lines(PreUnplugFetchRunner.PATTERNS_TAG)
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().contains("tue=11:40-11:50"))
    }
}
