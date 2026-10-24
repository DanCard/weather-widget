package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Footer labels for every hourly graph, both platforms (plans/261024-shared-hourly-footer-labels.md). */
@Category(ShortDuration::class)
class HourlyFooterLabelsTest {
    private val utc = ZoneId.of("UTC")

    private fun ms(t: LocalDateTime) = t.atZone(utc).toInstant().toEpochMilli()

    private fun hours(start: LocalDateTime, count: Int) = (0 until count).map { ms(start.plusHours(it.toLong())) }

    private fun labelledHours(times: List<Long>, span: Long, interval: Int) =
        HourlyFooterLabels.select(times, span, interval, utc).map { (times[it.index] - times[0]) / 3_600_000L }

    @Test
    fun `clock labels land on tidy clock hours, not on the window's left edge`() {
        // A 1 pm-start 18 h window at interval 4: Android counted from the edge (1p 5p 9p 1a 5a);
        // tidy clock hours are 4p 8p 12a 4a.
        val times = hours(LocalDateTime.of(2026, 6, 10, 13, 0), 19)
        val labels = HourlyFooterLabels.select(times, 18, 4, utc)
        assertEquals(listOf("4p", "8p", "12a", "4a"), labels.map { it.text })
        assertEquals(listOf(3L, 7L, 11L, 15L), labelledHours(times, 18, 4))
    }

    @Test
    fun `a missing hour does not shift the labels after it`() {
        // Android's precip/cloud graphs only counted hours with a forecast, so a gap moved every later label.
        val full = hours(LocalDateTime.of(2026, 6, 10, 12, 0), 13)
        val gappy = full.filterIndexed { i, _ -> i != 2 }
        val labelledFull = HourlyFooterLabels.labelledTimes(full, 12, 4, utc)
        val labelledGappy = HourlyFooterLabels.labelledTimes(gappy, 12, 4, utc)
        assertEquals(labelledFull, labelledGappy)
    }

    @Test
    fun `interval 1 labels every hour, and no hour is labelled for being now`() {
        val times = hours(LocalDateTime.of(2026, 6, 10, 9, 0), 6)
        assertEquals((0L..5L).toList(), labelledHours(times, 5, 1))
        // Interval 2 from 9 am: 10, 12, 2 — never 9 or 11, whichever of them is the current hour.
        assertEquals(listOf(1L, 3L, 5L), labelledHours(times, 5, 2))
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
            HourlyFooterLabels.labelledTimes(times, 48, 6, utc),
        )
    }

    @Test
    fun `date labels name the day`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            // 49 points: all of Jun 10, all of Jun 11, Jun 12 00:00 (its lone point is its label).
            val times = hours(LocalDateTime.of(2026, 6, 10, 0, 0), 49)
            val labels = HourlyFooterLabels.select(times, 72, 6, utc)
            assertEquals(listOf(12, 36, 48), labels.map { it.index })
            assertEquals(listOf("Wed 10", "Thu 11", "Fri 12"), labels.map { it.text })
            assertEquals(true, labels.all { it.isDate })
        } finally {
            Locale.setDefault(original)
        }
    }
}
