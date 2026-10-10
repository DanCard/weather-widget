package com.weatherwidget.shared.sourceview

import com.weatherwidget.data.local.RetentionPolicy
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The recency-weighted estimator both platforms will feed a fetch policy from, pinned against the
 * worked examples in plans/261010-source-view-tracking-table.md.
 */
@Category(ShortDuration::class)
class SourceViewProbabilityTest {
    // Saturday 2026-10-10; last Wednesday is age 3.
    private val today = LocalDate.of(2026, 10, 10)
    private val longAgo = today.minusDays(60)

    private fun row(
        daysAgo: Long,
        source: String = "OPEN_METEO",
        trigger: SourceViewTrigger = SourceViewTrigger.TOGGLE,
        wasPrimary: Boolean = false,
        view: SourceViewKind = SourceViewKind.DAILY,
        switches: Int = 1,
    ) = SourceViewDayRow(
        SourceViewTally.dayMs(today.minusDays(daysAgo)), source, view.name, trigger.name, wasPrimary, switches,
    )

    private fun toggle(vararg rows: SourceViewDayRow, since: LocalDate? = longAgo) =
        SourceViewProbability.toggleToday(rows.toList(), since, today)

    @Test
    fun `worked examples from the plan`() {
        assertEquals(0.157, toggle(row(3)).probability, 0.001)
        assertEquals(0.102, toggle(row(20)).probability, 0.001)
        assertEquals(0.312, toggle(row(1), row(2), row(3)).probability, 0.001)
        assertEquals(0.215, toggle(row(3), row(10), row(17), row(24)).probability, 0.001)
        assertEquals(0.390, toggle(row(3), since = today.minusDays(3)).probability, 0.001)
        assertEquals(0.123, toggle(row(10)).probability, 0.001)
        assertEquals(0.090, toggle().probability, 0.001)
        assertEquals(0.5, toggle(since = today).probability, 1e-9)
        assertEquals(0.5, toggle(since = null).probability, 1e-9)
    }

    @Test
    fun `weights and counted days`() {
        val e = toggle(row(3))
        assertEquals(9.115, e.effectiveDays, 0.001)
        assertEquals(0.743, e.weightedEvents, 0.001)
        assertEquals(30, e.countedDays)
    }

    @Test
    fun `home only and switches to the primary are not toggles, observations is`() {
        assertEquals(0.0, toggle(row(1, trigger = SourceViewTrigger.HOME, wasPrimary = true)).weightedEvents, 0.0)
        assertEquals(0.0, toggle(row(1, source = "NWS", wasPrimary = true)).weightedEvents, 0.0)
        assertTrue(toggle(row(1, trigger = SourceViewTrigger.OBSERVATIONS)).weightedEvents > 0)
        assertEquals(0.0, toggle(row(1, switches = 0)).weightedEvents, 0.0)
    }

    @Test
    fun `several rows on one day count that day once`() {
        assertEquals(
            toggle(row(3)).probability,
            toggle(row(3), row(3, source = "SILURIAN"), row(3, view = SourceViewKind.HOURLY)).probability,
            1e-12,
        )
    }

    @Test
    fun `today, future, before tracking and beyond the lookback are ignored`() {
        val base = toggle().probability
        assertEquals(base, toggle(row(0)).probability, 1e-12)
        assertEquals(base, toggle(row(-1)).probability, 1e-12)
        assertEquals(base, toggle(row(31)).probability, 1e-12)
        assertEquals(toggle(since = today.minusDays(2)).probability, toggle(row(5), since = today.minusDays(2)).probability, 1e-12)
    }

    @Test
    fun `source viewed - any switch counts, view filter, primary is certain`() {
        val rows = listOf(row(3, view = SourceViewKind.DAILY), row(5, source = "SILURIAN", view = SourceViewKind.HOURLY))
        fun p(id: String, kind: SourceViewKind? = null) =
            SourceViewProbability.sourceViewedToday(id, rows, longAgo, today, primarySourceId = "NWS", viewKind = kind).probability
        assertEquals(0.157, p("OPEN_METEO"), 0.001)
        assertEquals(0.090, p("OPEN_METEO", SourceViewKind.HOURLY), 0.001)
        assertTrue(p("SILURIAN", SourceViewKind.HOURLY) > 0.09)
        assertEquals(0.090, p("GOOGLE"), 0.001)
        assertEquals(1.0, p("NWS"), 0.0)
    }

    @Test
    fun `upper90 bounds the mean and narrows with data`() {
        assertEquals(0.2257, BetaQuantile.quantile(0.9, 1.0, 9.0), 1e-4)
        assertEquals(0.5, BetaQuantile.quantile(0.5, 3.0, 3.0), 1e-6)
        val thin = toggle(row(1), since = today.minusDays(3))
        val thick = toggle(*((1L..30L).filter { it % 4 == 0L }.map { row(it) }.toTypedArray()))
        assertTrue(thin.upper90 > thin.probability)
        assertTrue(thick.upper90 > thick.probability)
        assertTrue(thin.upper90 - thin.probability > thick.upper90 - thick.probability)
    }

    @Test
    fun `withinHours stretches a daily probability`() {
        assertEquals(0.3, SourceViewProbability.withinHours(0.3, 24.0), 1e-12)
        assertEquals(0.0, SourceViewProbability.withinHours(0.3, 0.0), 1e-12)
        assertEquals(1 - 0.7 * 0.7, SourceViewProbability.withinHours(0.3, 48.0), 1e-12)
    }

    @Test
    fun `summary line`() {
        val line = SourceViewProbability.summaryLine(listOf(row(3)), longAgo, today, "NWS", listOf("NWS", "OPEN_METEO"))
        val upper = String.format(java.util.Locale.US, "%.2f", toggle(row(3)).upper90)
        assertEquals("toggle=0.16 upper90=$upper days=30 NWS=1.00 OPEN_METEO=0.16", line)
    }

    @Test
    fun `retention is 30 days and the lookback follows it`() {
        assertEquals(30L, RetentionPolicy.SOURCE_VIEW_DAYS)
        assertEquals(RetentionPolicy.SOURCE_VIEW_DAYS, SourceViewProbability.LOOKBACK_DAYS)
    }
}

@Category(ShortDuration::class)
class SourceViewTallyTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `switch row - day, kind, primary flag`() {
        val now = ms(LocalDateTime.of(2026, 10, 10, 9, 0))
        val s = SourceViewTally.switchOf("OPEN_METEO", "NWS", ViewMode.DAILY, SourceViewTrigger.TOGGLE, now, zone)
        assertEquals(LocalDate.of(2026, 10, 10).toEpochDay() * SourceViewTally.DAY_MS, s.dateMs)
        assertEquals(SourceViewKind.DAILY, s.viewKind)
        assertFalse(s.wasPrimary)
        assertTrue(SourceViewTally.switchOf("NWS", "NWS", ViewMode.DAILY, SourceViewTrigger.HOME, now, zone).wasPrimary)
    }

    @Test
    fun `every graph view and the observations screen are hourly`() {
        val now = ms(LocalDateTime.of(2026, 10, 10, 9, 0))
        ViewMode.entries.filter { it.isGraphMode }.forEach {
            assertEquals(it.name, SourceViewKind.HOURLY, SourceViewTally.switchOf("X", "Y", it, SourceViewTrigger.TOGGLE, now, zone).viewKind)
        }
        assertEquals(
            SourceViewKind.HOURLY,
            SourceViewTally.switchOf("X", "Y", ViewMode.DAILY, SourceViewTrigger.OBSERVATIONS, now, zone).viewKind,
        )
    }

    @Test
    fun `day key is the local day either side of midnight`() {
        val before = SourceViewTally.dayMs(ms(LocalDateTime.of(2026, 10, 10, 23, 59, 59)), zone)
        val after = SourceViewTally.dayMs(ms(LocalDateTime.of(2026, 10, 11, 0, 0, 1)), zone)
        assertEquals(LocalDate.of(2026, 10, 10).toEpochDay() * SourceViewTally.DAY_MS, before)
        assertEquals(before + SourceViewTally.DAY_MS, after)
        assertEquals(LocalDate.of(2026, 10, 10), SourceViewDayRow(before, "X", "DAILY", "TOGGLE", false, 1).date)
    }
}

@Category(ShortDuration::class)
class SourceViewRetentionCutoffTest {
    @Test
    fun `cutoff keeps exactly the days the estimator reads`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val today = LocalDate.of(2026, 10, 10)
        val now = today.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val cutoff = SourceViewTally.retentionCutoffMs(now, zone)
        assertEquals(SourceViewTally.dayMs(today.minusDays(SourceViewProbability.LOOKBACK_DAYS)), cutoff)
    }
}

@Category(ShortDuration::class)
class SourceViewDailyLogTest {
    @Test
    fun `daily log is due once per local day`() {
        val zone = ZoneId.of("America/Los_Angeles")
        fun ms(h: Int, d: Int = 10) = LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(SourceViewProbability.isDailyLogDue(null, ms(9), zone))
        assertFalse(SourceViewProbability.isDailyLogDue(ms(1), ms(23), zone))
        assertTrue(SourceViewProbability.isDailyLogDue(ms(23, 9), ms(0, 10), zone))
    }
}
