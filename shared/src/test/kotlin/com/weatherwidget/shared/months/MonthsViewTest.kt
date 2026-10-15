package com.weatherwidget.shared.months

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyActualsAssembler
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.util.NavigationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.util.Locale

/** The months view's shared rules; both platforms call these and only draw the result. */
@Category(ShortDuration::class)
class MonthsViewTest {

    private val today = LocalDate.parse("2026-10-15")

    private fun row(date: LocalDate, hi: Float?, lo: Float?, fHi: Float? = null, fLo: Float? = null, lat: Double = 37.42, lon: Double = -122.08) =
        DailyHistory(
            date = date.toEpochDay() * 86_400_000L,
            source = WeatherSource.NWS.id,
            locationLat = lat,
            locationLon = lon,
            computedHighTemp = hi,
            computedLowTemp = lo,
            condition = "x",
            updatedAt = 1L,
            forecastHighTemp = fHi,
            forecastLowTemp = fLo,
        )

    // --- span ---

    @Test
    fun `span steps out and in and defaults to two months`() {
        assertEquals(MonthsSpan.TWO, MonthsSpan.DEFAULT)
        assertEquals(MonthsSpan.TWO, MonthsSpan.ONE.wider())
        assertEquals(MonthsSpan.SIX, MonthsSpan.TWO.wider())
        assertNull(MonthsSpan.TWELVE.wider())
        assertNull(MonthsSpan.ONE.narrower())
        assertEquals(MonthsSpan.TWO, MonthsSpan.fromMonths(7))
    }

    // --- density + zoom state machine ---

    @Test
    fun `density threshold is per column width`() {
        assertFalse(DailyZoomDensity.tooDense(280f, 10))
        assertTrue(DailyZoomDensity.tooDense(279f, 10))
        assertEquals(10, DailyZoomDensity.maxColumns(280f))
    }

    @Test
    fun `daily zoom out adds a column until too dense, then opens one month at the right edge`() {
        val edge = today.plusDays(7)
        assertEquals(MonthsZoomAction.AddHistoryColumn, MonthsZoomController.dailyZoomOut(560f, 19, true, edge))
        assertEquals(
            MonthsZoomAction.OpenMonths(MonthsSpan.ONE, edge),
            MonthsZoomController.dailyZoomOut(560f, 20, true, edge),
        )
    }

    @Test
    fun `daily zoom out with no more history opens months even when columns are wide`() {
        assertEquals(
            MonthsZoomAction.OpenMonths(MonthsSpan.ONE, today),
            MonthsZoomController.dailyZoomOut(900f, 5, false, today),
        )
    }

    @Test
    fun `months zoom is reversible end to end`() {
        assertEquals(MonthsZoomAction.SetSpan(MonthsSpan.SIX), MonthsZoomController.monthsZoomOut(MonthsSpan.TWO))
        assertEquals(MonthsZoomAction.None, MonthsZoomController.monthsZoomOut(MonthsSpan.TWELVE))
        val window = MonthsViewport.window(MonthsSpan.ONE, today)
        assertEquals(MonthsZoomAction.SetSpan(MonthsSpan.ONE), MonthsZoomController.monthsZoomIn(MonthsSpan.TWO, window))
        assertEquals(MonthsZoomAction.BackToDaily(today), MonthsZoomController.monthsZoomIn(MonthsSpan.ONE, window))
        assertEquals(MonthsZoomAction.OpenDaily(today.minusDays(60)), MonthsZoomController.monthsTap(today.minusDays(60)))
    }

    // --- viewport ---

    @Test
    fun `window covers the span ending on the right edge`() {
        val w = MonthsViewport.window(MonthsSpan.TWO, LocalDate.parse("2026-10-31"))
        assertEquals(LocalDate.parse("2026-09-01"), w.start)
        assertEquals(61, w.dayCount)
    }

    @Test
    fun `clamp keeps the window over the data`() {
        val bounds = LocalDate.parse("2026-03-01")..LocalDate.parse("2026-10-29")
        assertEquals(bounds.endInclusive, MonthsViewport.clampEnd(LocalDate.parse("2026-12-01"), MonthsSpan.TWO, bounds))
        assertEquals(LocalDate.parse("2026-04-30"), MonthsViewport.clampEnd(LocalDate.parse("2026-01-01"), MonthsSpan.TWO, bounds))
        // Data shorter than the span: end on the last day.
        val short = LocalDate.parse("2026-10-01")..LocalDate.parse("2026-10-29")
        assertEquals(short.endInclusive, MonthsViewport.clampEnd(LocalDate.parse("2026-09-01"), MonthsSpan.SIX, short))
    }

    @Test
    fun `default end is the forecast tail, or today`() {
        assertEquals(today.plusDays(10), MonthsViewport.defaultEnd(today, today.minusDays(200)..today.plusDays(10)))
        assertEquals(today, MonthsViewport.defaultEnd(today, null))
    }

    @Test
    fun `tap x maps to the day under it, edges snap`() {
        val w = MonthsWindow(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))
        assertEquals(LocalDate.parse("2026-09-01"), MonthsViewport.dateAtX(-50f, w, 0f, 300f))
        assertEquals(LocalDate.parse("2026-09-30"), MonthsViewport.dateAtX(999f, w, 0f, 300f))
        assertEquals(LocalDate.parse("2026-09-16"), MonthsViewport.dateAtX(155f, w, 0f, 300f))
        // xForDate and dateAtX round-trip across a month boundary.
        val w2 = MonthsWindow(LocalDate.parse("2026-08-20"), LocalDate.parse("2026-10-19"))
        val aug31 = LocalDate.parse("2026-08-31")
        assertEquals(aug31, MonthsViewport.dateAtX(MonthsViewport.xForDate(aug31, w2, 10f, 600f), w2, 10f, 600f))
    }

    @Test
    fun `dragging right shows earlier days`() {
        val w = MonthsWindow(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))
        assertEquals(-3, MonthsViewport.dragDays(30f, 300f, w))
        assertEquals(2, MonthsViewport.dragDays(-20f, 300f, w))
    }

    // --- jump into the daily view ---

    @Test
    fun `tapped date lands in the middle column of the daily window`() {
        for (skip in listOf(true, false)) {
            for (cols in listOf(5, 7, 9)) {
                for (back in listOf(0L, 1L, 3L, 45L, 400L)) {
                    val date = today.minusDays(back)
                    val offset = MonthsDailyJump.offsetCentring(date, today, cols, skip)
                    val (first, last) = NavigationUtils.getVisibleDateRange(today, offset, cols, skip)
                    assertTrue("cols=$cols skip=$skip back=$back", !date.isBefore(first) && !date.isAfter(last))
                    if (back >= 45) assertEquals("cols=$cols skip=$skip", first.plusDays(((cols - 1) / 2).toLong()), date)
                }
            }
        }
    }

    @Test
    fun `zoom back in keeps the right edge`() {
        val edge = today.minusDays(30)
        val offset = MonthsDailyJump.offsetWithRightEdge(edge, today, 7, false)
        assertEquals(edge, NavigationUtils.getVisibleDateRange(today, offset, 7, false).second)
    }

    // --- series ---

    @Test
    fun `past days use the actual, else the frozen forecast dashed, future is the forecast`() {
        val values = MonthsSeriesBuilder.values(
            pastActuals = mapOf(
                today.minusDays(2) to row(today.minusDays(2), 70f, 50f),
                today.minusDays(1) to row(today.minusDays(1), null, null, fHi = 72f, fLo = 52f),
                today.minusDays(3) to row(today.minusDays(3), null, null),
            ),
            forecasts = mapOf(today to (74f to 53f), today.plusDays(1) to (null to null), today.minusDays(5) to (1f to 1f)),
            today = today,
        )
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), values.map { it.date })
        assertEquals(
            listOf(MonthsDayValue.Kind.ACTUAL, MonthsDayValue.Kind.PAST_FORECAST, MonthsDayValue.Kind.FORECAST),
            values.map { it.kind },
        )
    }

    @Test
    fun `gaps break the line and style changes bridge from the previous point`() {
        val d = { n: Long -> today.minusDays(n) }
        val values = listOf(
            MonthsDayValue(d(6), 60f, 40f, MonthsDayValue.Kind.ACTUAL),
            MonthsDayValue(d(5), 61f, 41f, MonthsDayValue.Kind.ACTUAL),
            // d(4) missing: gap
            MonthsDayValue(d(3), 62f, null, MonthsDayValue.Kind.ACTUAL),
            MonthsDayValue(d(2), 63f, 43f, MonthsDayValue.Kind.ACTUAL),
            MonthsDayValue(d(1), 64f, 44f, MonthsDayValue.Kind.PAST_FORECAST),
        )
        val highs = MonthsSeriesBuilder.segments(values, MonthsLine.HIGH)
        assertEquals(listOf(listOf(d(6), d(5)), listOf(d(3), d(2)), listOf(d(2), d(1))), highs.map { s -> s.points.map { it.first } })
        assertEquals(listOf(false, false, true), highs.map { it.dashed })
        // No interpolation: the values are the raw ones.
        assertEquals(listOf(62f, 63f), highs[1].points.map { it.second })

        val lows = MonthsSeriesBuilder.segments(values, MonthsLine.LOW)
        // d(3) has no low: d(2) starts alone, then bridges into the dashed d(1).
        assertEquals(listOf(listOf(d(6), d(5)), listOf(d(2)), listOf(d(2), d(1))), lows.map { s -> s.points.map { it.first } })
    }

    @Test
    fun `the months line reads the same actuals as the daily view's 30-day load`() {
        val lat = 37.42
        val lon = -122.08
        val zone = ZoneId.of("America/Los_Angeles")
        val rows = (1L..500L).map { n ->
            row(today.minusDays(n), 60f + n % 11, 40f + n % 7)
        } + (1L..40L).map { n ->
            // A second fragment a few hundred metres off; the nearest must win in both loads.
            row(today.minusDays(n), 99f, 1f, lat = lat + 0.004, lon = lon)
        }
        fun assemble(lookback: Long) = DailyActualsAssembler.assemble(
            activeSources = listOf(WeatherSource.NWS),
            pastRows = rows.filter { !it.toLocalDate().isBefore(today.minusDays(lookback)) },
            yesterdayDonors = emptyList(),
            observations = emptyList(),
            hourlyForecasts = emptyList(),
            latitude = lat,
            longitude = lon,
            today = today,
            zone = zone,
            nowMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            personalStationWeight = 1.0,
        ).bySource.getValue(WeatherSource.NWS.id)
        val widget = assemble(30)
        val months = assemble(547)
        assertEquals(500, months.size)
        assertEquals(widget, months.filterKeys { it in widget.keys })
    }

    // --- axis ---

    @Test
    fun `month ticks carry the year on the first month and on January`() {
        val ticks = MonthsAxis.ticks(MonthsWindow(LocalDate.parse("2025-11-20"), LocalDate.parse("2026-02-10")), MonthsSpan.SIX, Locale.US)
        assertEquals(listOf("Dec 2025", "Jan 2026", "Feb"), ticks.filter { it.major }.map { it.label })
    }

    @Test
    fun `one month gets day ticks`() {
        val ticks = MonthsAxis.ticks(MonthsWindow(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-10-15")), MonthsSpan.ONE, Locale.US)
        assertEquals(listOf("22", "Oct 2026", "8", "15"), ticks.map { it.label })
    }

    // --- geometry ---

    @Test
    fun `geometry splits lines into coloured pieces, keeps lone days as dots, and marks today`() {
        val d = { n: Long -> today.minusDays(n) }
        val values = listOf(
            MonthsDayValue(d(9), 60f, 40f, MonthsDayValue.Kind.ACTUAL),
            MonthsDayValue(d(8), 62f, 41f, MonthsDayValue.Kind.ACTUAL),
            MonthsDayValue(d(5), 65f, 45f, MonthsDayValue.Kind.ACTUAL),
            MonthsDayValue(today, 70f, 50f, MonthsDayValue.Kind.FORECAST),
            MonthsDayValue(today.plusDays(1), 71f, 51f, MonthsDayValue.Kind.FORECAST),
        )
        val window = MonthsViewport.window(MonthsSpan.ONE, today.plusDays(1))
        val g = MonthsGraphGeometry.compute(
            MonthsGraphInput(window, MonthsSpan.ONE, today, values, mapOf(MonthDay.from(today) to (68f to 48f)), useCelsius = false),
            widthPx = 600f, heightPx = 300f, density = 1f, measureText = { t, size -> t.length * size * 0.6f },
        )
        assertTrue(g.hasData)
        // highs: (d9,d8) solid + (today,+1) dashed; lows the same → 4 pieces; d(5) alone on both lines → 2 dots.
        assertEquals(4, g.pieces.size)
        assertEquals(listOf(false, true, false, true), g.pieces.map { it.dashed })
        assertEquals(2, g.dots.size)
        assertEquals(MonthsViewport.xForDate(today, window, g.plotLeft, g.plotWidth), g.todayX!!, 0.01f)
        assertEquals(today, g.dateAt(g.todayX!!))
        // Warmer piece sits higher (smaller y).
        assertTrue(g.pieces[1].y1 < g.pieces[0].y1)
        assertEquals(1, g.normalHigh.size)
        assertTrue(g.labels.isNotEmpty())
        g.labels.forEach { assertTrue(it.x >= g.plotLeft) }
    }

    @Test
    fun `empty window still lays out an axis`() {
        val window = MonthsViewport.window(MonthsSpan.TWO, today)
        val g = MonthsGraphGeometry.compute(
            MonthsGraphInput(window, MonthsSpan.TWO, today, emptyList(), emptyMap(), useCelsius = true),
            600f, 300f, 2f, { t, size -> t.length * size * 0.6f },
        )
        assertFalse(g.hasData)
        assertTrue(g.gridLines.isNotEmpty())
        assertTrue(g.pieces.isEmpty())
    }

    @Test
    fun `labels are each month's extremes and never overlap`() {
        val values = (0L until 60L).map { n ->
            val date = today.minusDays(n)
            MonthsDayValue(date, 60f + (n % 9), 40f + (n % 5), MonthsDayValue.Kind.ACTUAL)
        }
        val window = MonthsViewport.window(MonthsSpan.TWO, today)
        val g = MonthsGraphGeometry.compute(
            MonthsGraphInput(window, MonthsSpan.TWO, today, values, emptyMap(), useCelsius = false),
            300f, 200f, 1f, { t, size -> t.length * size * 0.6f },
        )
        val rects = g.labels.map { floatArrayOf(it.x, it.baseline - 12f * 0.8f, it.x + it.text.length * 12f * 0.6f, it.baseline) }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]
            val b = rects[j]
            val overlap = minOf(a[2], b[2]) > maxOf(a[0], b[0]) && minOf(a[3], b[3]) > maxOf(a[1], b[1])
            assertFalse("labels $i and $j overlap", overlap)
        }
        val candidates = MonthsGraphGeometry.labelCandidates(values, window)
        assertEquals(0, candidates.first().priority)
        assertEquals(68f, candidates.filter { it.line == MonthsLine.HIGH }.maxOf { it.tempF }, 0f)
    }
}
