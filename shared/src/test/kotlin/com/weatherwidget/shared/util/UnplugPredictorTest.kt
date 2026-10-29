package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.ChargeHistory.Session
import com.weatherwidget.shared.util.UnplugPredictor.Kind
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** plans/261028-learned-unplug-prediction.md */
@Category(ShortDuration::class)
class UnplugPredictorTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    // Mon 2026-11-02, the evening before a basketball Tuesday.
    private val monday = LocalDate.of(2026, 11, 2)
    private val now = ms(monday.atTime(22, 0))

    /** A charge from [fromH]:00 the day before to [date] [h]:[m]. */
    private fun charge(date: LocalDate, h: Int, m: Int, fromH: Int = 20) =
        Session(ms(date.minusDays(1).atTime(fromH, 0)), ms(date.atTime(h, m)), exact = true)

    private fun state(vararg sessions: Session, alarms: List<Long> = emptyList()) =
        ChargeHistory.State(sessions = sessions.sortedBy { it.unpluggedAtMs }, alarms = alarms)

    private fun tuesday(weeksBack: Int) = monday.plusDays(1).minusWeeks(weeksBack.toLong())

    @Test
    fun `two Tuesdays at 11-40 and 11-50 predict next Tuesday at 11-40`() {
        // The Fold: unplugged Tue 10-20 11:50 and Tue 10-27 11:40 (basketball).
        val s = state(charge(tuesday(2), 11, 50), charge(tuesday(1), 11, 40))
        val p = UnplugPredictor.predictNext(s, null, now, zone)
        assertNotNull(p)
        assertEquals(ms(tuesday(0).atTime(11, 40)), p!!.atMs)
        assertEquals(Kind.WEEKLY, p.kind)
        assertEquals("weekly(tue)", p.source)
    }

    @Test
    fun `one Tuesday is not a habit`() {
        assertNull(UnplugPredictor.predictNext(state(charge(tuesday(1), 11, 40)), null, now, zone))
    }

    @Test
    fun `Tuesdays the phone stayed on the charger through the window count against the habit`() {
        val stayed = (1..3).map { Session(ms(tuesday(it).atTime(8, 0)), ms(tuesday(it).atTime(18, 0)), true) }
        val s = state(charge(tuesday(5), 11, 40), charge(tuesday(4), 11, 45), *stayed.toTypedArray())
        // The 18:00 unplugs are a Tuesday-evening cluster of their own.
        val tue = UnplugPredictor.patterns(s, now, zone).single { it.label == "tue" && it.earliestMinute < 12 * 60 }
        assertEquals(2, tue.hits)
        assertEquals(3, tue.misses)
        assertFalse(tue.active)
        assertTrue(UnplugPredictor.predictNext(s, null, now, zone)?.atMs != ms(tuesday(0).atTime(11, 40)))
    }

    @Test
    fun `a Tuesday off the charger is no evidence either way`() {
        // Tue 10-27 the phone was on battery all morning: neither hit nor miss.
        val s = state(charge(tuesday(3), 11, 40), charge(tuesday(2), 11, 50))
        val tue = UnplugPredictor.patterns(s, now, zone).single { it.label == "tue" }
        assertEquals(0, tue.misses)
        assertTrue(tue.active)
    }

    @Test
    fun `old hits fade behind a recent miss`() {
        // Hits 6 and 7 weeks ago, a miss last week: 2 of 3 by count, but under 0.5 once weighted.
        val stayed = Session(ms(tuesday(1).atTime(8, 0)), ms(tuesday(1).atTime(18, 0)), true)
        val s = state(charge(tuesday(7), 11, 40), charge(tuesday(6), 11, 40), stayed)
        val tue = UnplugPredictor.patterns(s, now, zone).single { it.label == "tue" }
        assertEquals(2, tue.hits)
        assertEquals(1, tue.misses)
        assertTrue("weighted rate ${tue.rate}", tue.rate < UnplugPredictor.MIN_HIT_RATE)
        assertFalse(tue.active)
    }

    @Test
    fun `four weekday unplugs near 07-00 make a daily habit`() {
        val days = listOf(3, 4, 5, 6).map { monday.minusDays(it.toLong()) } // Fri, Thu, Wed, Tue
        val s = state(*days.mapIndexed { i, d -> charge(d, 7, 5 + i * 5, fromH = 23) }.toTypedArray())
        val p = UnplugPredictor.predictNext(s, null, now, zone)
        assertEquals(Kind.DAILY, p!!.kind)
        assertEquals("weekday", p.label)
        assertEquals(ms(tuesday(0).atTime(7, 5)), p.atMs)
    }

    @Test
    fun `a bare alarm with no history predicts nothing`() {
        val alarm = ms(monday.atTime(22, 30))
        assertFalse(UnplugPredictor.alarmTrust(state(), alarm, now, zone).trusted)
        assertNull(UnplugPredictor.predictNext(state(), alarm, now, zone))
    }

    @Test
    fun `a bedtime alarm the phone sleeps through on the charger loses trust`() {
        // 22:00 alarms on the two previous nights, charging 18:00–10:00 through both.
        val alarms = listOf(1L, 2L).map { ms(monday.minusDays(it).atTime(22, 0)) }
        val charges = listOf(1L, 2L).map { Session(ms(monday.minusDays(it).atTime(18, 0)), ms(monday.minusDays(it - 1).atTime(10, 0)), true) }
        val s = state(*charges.toTypedArray(), alarms = alarms)
        val trust = UnplugPredictor.alarmTrust(s, ms(monday.plusDays(1).atTime(22, 0)), now, zone)
        assertEquals(0, trust.hits)
        assertEquals(2, trust.misses)
        assertFalse(trust.trusted)
    }

    @Test
    fun `an alarm unplugs follow earns trust and predicts at its learned offset`() {
        val weekdayNight = monday.minusDays(3) // Fri
        val alarm = ms(weekdayNight.atTime(6, 30))
        val s = state(
            Session(ms(weekdayNight.minusDays(1).atTime(23, 0)), alarm + 25 * 60_000L, true),
            alarms = listOf(alarm),
        )
        val next = ms(tuesday(0).atTime(6, 30))
        val trust = UnplugPredictor.alarmTrust(s, next, now, zone)
        assertEquals(1, trust.hits)
        assertTrue(trust.trusted)
        assertEquals(25, trust.offsetMinutes)
        val p = UnplugPredictor.predictNext(s, next, now, zone)
        assertEquals(Kind.ALARM, p!!.kind)
        assertEquals(next + 25 * 60_000L, p.atMs)
    }

    @Test
    fun `a window an unplug already answered is not predicted again`() {
        val s0 = state(charge(tuesday(2), 11, 50), charge(tuesday(1), 11, 40))
        // Today is Tuesday 11:30; the phone came off at 11:20 and went back on.
        val tueNow = ms(tuesday(0).atTime(11, 30))
        val s = s0.copy(sessions = s0.sessions + Session(ms(tuesday(0).atTime(0, 0)), ms(tuesday(0).atTime(11, 20)), true))
        val p = UnplugPredictor.predictNext(s, null, tueNow, zone)
        assertTrue("next is a week out or none: $p", p == null || p.atMs > ms(tuesday(0).atTime(23, 59)))
    }

    @Test
    fun `the log line names patterns and the alarm's trust`() {
        val s = state(charge(tuesday(2), 11, 50), charge(tuesday(1), 11, 40))
        val line = UnplugPredictor.logLine(s, ms(monday.atTime(22, 30)), now, zone)
        assertTrue(line, line.contains("tue=11:40-11:50 p=1.00(2/0) on"))
        assertTrue(line, line.contains("alarm=22:30 p=0.50(0/0) off"))
    }
}
