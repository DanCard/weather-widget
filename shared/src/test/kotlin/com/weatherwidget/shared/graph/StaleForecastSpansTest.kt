package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.StaleForecastSpans.Span
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class StaleForecastSpansTest {
    private val h = 3_600_000L
    private val threeAm = 1_000 * h
    private val now = threeAm + 36 * 60_000L // 03:36

    @Test
    fun `desktop asleep - 18-45 fetch for 3am is stale, Fold's 01-13 is not`() {
        val desktop = StaleForecastSpans.isStale(threeAm, threeAm - (8 * h + 15 * 60_000L), now)
        val fold = StaleForecastSpans.isStale(threeAm, threeAm - (h + 47 * 60_000L), now)
        println("isStale desktop=$desktop fold=$fold")
        assertTrue(desktop)
        assertFalse(fold)
    }

    @Test
    fun `boundary - exactly 3 h is fresh, a millisecond more is stale`() {
        assertFalse(StaleForecastSpans.isStale(threeAm, threeAm - 3 * h, now))
        assertTrue(StaleForecastSpans.isStale(threeAm, threeAm - 3 * h - 1, now))
    }

    @Test
    fun `unstarted hour, synthesized row and in-hour fetch are never stale`() {
        assertFalse("hour not started", StaleForecastSpans.isStale(now + h, 0L + 1, now))
        assertFalse("synthesized fetchedAt=0", StaleForecastSpans.isStale(threeAm, 0L, now))
        assertFalse("fetched after the hour began", StaleForecastSpans.isStale(threeAm, threeAm + 600_000L, now))
    }

    @Test
    fun `no stale hours - one fresh span`() {
        assertEquals(
            listOf(Span(0f, 400f, false)),
            StaleForecastSpans.spans(listOf(0f, 100f, 200f), listOf(false, false, false), 0f, 400f),
        )
    }

    @Test
    fun `stale run merges and splits at midpoints`() {
        val spans = StaleForecastSpans.spans(
            xs = listOf(0f, 100f, 200f, 300f, 400f),
            stale = listOf(true, true, true, false, false),
            left = 0f,
            right = 400f,
        )
        println("spans=$spans")
        assertEquals(listOf(Span(0f, 250f, true), Span(250f, 400f, false)), spans)
    }

    @Test
    fun `alpha scales only when stale`() {
        assertEquals(255, StaleForecastSpans.alphaFor(255, stale = false))
        assertEquals(102, StaleForecastSpans.alphaFor(255, stale = true))
    }
}

@Category(ShortDuration::class)
class HourDataAssemblerStaleTest {
    @Test
    fun `sub-hourly points inherit their local hour's flag, half-hour zone included`() {
        val zone = java.time.ZoneId.of("Asia/Kolkata") // UTC+5:30: epoch-hour math would miss
        val threeAm = java.time.LocalDateTime.of(2026, 11, 4, 3, 0).atZone(zone).toInstant().toEpochMilli()
        val m = 60_000L
        fun pt(t: Long) = com.weatherwidget.shared.actuals.ActualTemperaturePoint(t, 55f, null, false, false)
        val series = com.weatherwidget.shared.actuals.ActualTemperatureSeriesResult(
            points = listOf(pt(threeAm - 30 * m), pt(threeAm), pt(threeAm + 20 * m), pt(threeAm + 60 * m)),
            blendStats = null,
            selectedStationId = null,
            sourceObservationCount = 0,
            blendInputCount = 0,
        )
        val flags = HourDataAssembler.assembleHourData(series, zone, setOf(threeAm)).map { it.isStaleForecast }
        println("assembler stale flags=$flags")
        assertEquals(listOf(false, true, true, false), flags)
    }
}
