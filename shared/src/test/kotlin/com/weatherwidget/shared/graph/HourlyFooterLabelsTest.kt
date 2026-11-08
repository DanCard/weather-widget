package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Footer labels for every hourly graph, both platforms (plans/261024-shared-hourly-footer-labels.md,
 * plans/261107-hourly-footer-labels-from-window-start.md).
 */
@Category(ShortDuration::class)
class HourlyFooterLabelsTest {
    private val utc = ZoneId.of("UTC")

    private fun ms(t: LocalDateTime) = t.atZone(utc).toInstant().toEpochMilli()

    private fun hours(start: LocalDateTime, count: Int) = (0 until count).map { ms(start.plusHours(it.toLong())) }

    private fun labelledHours(times: List<Long>, span: Long, interval: Int) =
        HourlyFooterLabels.select(times, times[0], span, interval, utc).map { (times[it.index] - times[0]) / 3_600_000L }

    @Test
    fun `labels start at the window's first hour, then every interval hours`() {
        // Emulator 2026-11-07: the 18 h window ran 10:00..04:00 at interval 4 and the footer read
        // 12p 4p 8p 12a 4a, leaving the left corner unlabelled. It now starts at 10a.
        val start = LocalDateTime.of(2026, 11, 7, 10, 0)
        val times = hours(start, 19)
        val labels = HourlyFooterLabels.select(times, ms(start), 18, 4, utc)
        println("window-start labels: ${labels.map { "${it.index}:${it.text}" }}")
        assertEquals(listOf("10a", "2p", "6p", "10p", "2a"), labels.map { it.text })
        assertEquals(listOf(0, 4, 8, 12, 16), labels.map { it.index })
    }

    @Test
    fun `interval 3 counts from the window start, not from midnight`() {
        val start = LocalDateTime.of(2026, 11, 7, 10, 0)
        val times = hours(start, 19)
        assertEquals(
            listOf("10a", "1p", "4p", "7p", "10p", "1a", "4a"),
            HourlyFooterLabels.select(times, ms(start), 18, 3, utc).map { it.text },
        )
    }

    @Test
    fun `a window starting off the hour anchors at the next whole hour`() {
        // Desktop pans by fractions of an hour; 10:20 anchors at 11:00.
        val windowStart = LocalDateTime.of(2026, 11, 7, 10, 20)
        val times = listOf(ms(windowStart)) + hours(LocalDateTime.of(2026, 11, 7, 11, 0), 12)
        assertEquals(
            listOf("11a", "3p", "7p"),
            HourlyFooterLabels.select(times, ms(windowStart), 12, 4, utc).map { it.text },
        )
    }

    @Test
    fun `a missing hour does not shift the labels after it`() {
        // The precip/cloud graphs pass only hours with a forecast. Losing the FIRST hour must not move
        // the anchor (it is the window start, not the first point), nor must a gap mid-window.
        val start = LocalDateTime.of(2026, 6, 10, 12, 0)
        val full = hours(start, 13)
        val labelledFull = HourlyFooterLabels.labelledTimes(full, ms(start), 12, 4, utc)
        for (missing in listOf(0, 2)) {
            val gappy = full.filterIndexed { i, _ -> i != missing }
            assertEquals(
                "missing index $missing",
                labelledFull - full[missing],
                HourlyFooterLabels.labelledTimes(gappy, ms(start), 12, 4, utc),
            )
        }
    }

    @Test
    fun `the cadence counts elapsed hours across a DST change`() {
        // US fall-back 2026-11-01: 01:00 happens twice. Labels stay 4 real hours apart.
        val la = ZoneId.of("America/Los_Angeles")
        val startMs = LocalDateTime.of(2026, 10, 31, 22, 0).atZone(la).toInstant().toEpochMilli()
        val times = (0..12).map { startMs + it * 3_600_000L }
        val labels = HourlyFooterLabels.select(times, startMs, 12, 4, la)
        assertEquals(listOf(0, 4, 8, 12), labels.map { it.index })
        assertEquals(listOf("10p", "1a", "5a", "9a"), labels.map { it.text })
    }

    @Test
    fun `interval 1 labels every hour, and no hour is labelled for being now`() {
        val times = hours(LocalDateTime.of(2026, 6, 10, 9, 0), 6)
        assertEquals((0L..5L).toList(), labelledHours(times, 5, 1))
        // Interval 2 from a 9 am window start: 9, 11, 1 — whichever of them is the current hour.
        assertEquals(listOf(0L, 2L, 4L), labelledHours(times, 5, 2))
    }

    @Test
    fun `date mode picks local noon for each fully visible day`() {
        val times = hours(LocalDateTime.of(2026, 6, 10, 0, 0), 48)
        assertEquals(listOf(12L, 36L), labelledHours(times, 48, 6))
    }

    @Test
    fun `date mode takes the nearest available hour for partial first and last days`() {
        // Starts Jun 10 15:00 (noon already past), ends Jun 12 15:00.
        val start = LocalDateTime.of(2026, 6, 10, 15, 0)
        val times = hours(start, 49)
        assertEquals(
            setOf(ms(start), ms(LocalDateTime.of(2026, 6, 11, 12, 0)), ms(LocalDateTime.of(2026, 6, 12, 12, 0))),
            HourlyFooterLabels.labelledTimes(times, times[0], 48, 6, utc),
        )
    }

    @Test
    fun `date labels name the day`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            // 49 points: all of Jun 10, all of Jun 11, Jun 12 00:00 (its lone point is its label).
            val times = hours(LocalDateTime.of(2026, 6, 10, 0, 0), 49)
            val labels = HourlyFooterLabels.select(times, times[0], 72, 6, utc)
            assertEquals(listOf(12, 36, 48), labels.map { it.index })
            assertEquals(listOf("Wed 10", "Thu 11", "Fri 12"), labels.map { it.text })
            assertEquals(true, labels.all { it.isDate })
        } finally {
            Locale.setDefault(original)
        }
    }
}
