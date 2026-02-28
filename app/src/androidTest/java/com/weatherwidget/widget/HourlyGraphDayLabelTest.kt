package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Instrumented tests for day label placement on the hourly temperature and rain graphs.
 *
 * Verifies left/right edge labels, today highlighting, and same-day windows,
 * via the onDayLabelPlaced callback — no pixel inspection.
 */
@RunWith(AndroidJUnit4::class)
class HourlyGraphDayLabelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeHours(
        start: LocalDateTime,
        count: Int,
        temp: Float = 60f,
    ): List<TemperatureGraphRenderer.HourData> =
        (0 until count).map { i ->
            val dt = start.plusHours(i.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = dt,
                temperature = temp,
                label = if (dt.hour == 0) "12a" else "${dt.hour % 12}${if (dt.hour < 12) "a" else "p"}",
                showLabel = i % 4 == 0,
            )
        }

    private fun makePrecipHours(
        start: LocalDateTime,
        count: Int,
    ): List<PrecipitationGraphRenderer.PrecipHourData> =
        (0 until count).map { i ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = 10,
                label = if (dt.hour == 0) "12a" else "${dt.hour % 12}${if (dt.hour < 12) "a" else "p"}",
                showLabel = i % 4 == 0,
            )
        }

    private fun renderTemp(
        hours: List<TemperatureGraphRenderer.HourData>,
        currentTime: LocalDateTime = hours[hours.size / 2].dateTime,
        widthPx: Int = 800,
        heightPx: Int = 300,
    ): List<TemperatureGraphRenderer.DayLabelPlacementDebug> {
        val results = mutableListOf<TemperatureGraphRenderer.DayLabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = currentTime,
            onDayLabelPlaced = { results.add(it) },
        )
        return results
    }

    private fun renderPrecip(
        hours: List<PrecipitationGraphRenderer.PrecipHourData>,
        currentTime: LocalDateTime = hours[hours.size / 2].dateTime,
        widthPx: Int = 800,
        heightPx: Int = 300,
    ): List<PrecipitationGraphRenderer.DayLabelPlacementDebug> {
        val results = mutableListOf<PrecipitationGraphRenderer.DayLabelPlacementDebug>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = currentTime,
            onDayLabelPlaced = { results.add(it) },
        )
        return results
    }

    // ── Temperature graph tests ───────────────────────────────────────────────

    @Test
    fun tempGraph_alwaysProducesTwoLabels() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0) // past date, not today
        val hours = makeHours(start, count = 24)
        val labels = renderTemp(hours)
        assertEquals("Expected exactly 2 day labels", 2, labels.size)
    }

    @Test
    fun tempGraph_leftLabelAnchoredToFirstHour() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val hours = makeHours(start, count = 24)
        val labels = renderTemp(hours)
        val left = labels.first { it.side == "LEFT" }
        assertEquals(start.toLocalDate(), left.date)
    }

    @Test
    fun tempGraph_rightLabelAnchoredToLastHour() {
        val start = LocalDateTime.of(2025, 6, 14, 20, 0)
        val hours = makeHours(start, count = 24) // spans into June 15
        val labels = renderTemp(hours)
        val right = labels.first { it.side == "RIGHT" }
        assertEquals(start.plusHours(23).toLocalDate(), right.date)
    }

    @Test
    fun tempGraph_sameDayWindow_bothLabelsAreSameDay() {
        // 4am–8am same day — overnight trough window
        val start = LocalDateTime.of(2025, 6, 15, 4, 0)
        val hours = makeHours(start, count = 5)
        val labels = renderTemp(hours)
        val left  = labels.first { it.side == "LEFT" }
        val right = labels.first { it.side == "RIGHT" }
        assertEquals("Both sides should be same date", left.date, right.date)
        assertEquals("Both day names should match", left.dayText, right.dayText)
    }

    @Test
    fun tempGraph_spansMidnight_leftAndRightAreDifferentDays() {
        // 8pm Sat through 8am Sun
        val start = LocalDateTime.of(2025, 6, 14, 20, 0) // Saturday
        val hours = makeHours(start, count = 13)
        val labels = renderTemp(hours)
        val left  = labels.first { it.side == "LEFT" }
        val right = labels.first { it.side == "RIGHT" }
        assertFalse("Left and right should be different days", left.date == right.date)
    }

    @Test
    fun tempGraph_todayOnLeft_markedIsToday() {
        val today = LocalDate.now()
        val start = today.atTime(8, 0)
        val hours = makeHours(start, count = 24)
        val labels = renderTemp(hours, currentTime = start.plusHours(4))
        val left = labels.first { it.side == "LEFT" }
        assertTrue("Left label should be marked isToday", left.isToday)
    }

    @Test
    fun tempGraph_pastDateOnLeft_notMarkedIsToday() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0) // past date
        val hours = makeHours(start, count = 24)
        val labels = renderTemp(hours)
        val left = labels.first { it.side == "LEFT" }
        assertFalse("Past date should not be marked isToday", left.isToday)
    }

    @Test
    fun tempGraph_todayOnRight_markedIsToday() {
        val today = LocalDate.now()
        // Window starts yesterday, ends today
        val start = today.minusDays(1).atTime(20, 0)
        val hours = makeHours(start, count = 13)
        val labels = renderTemp(hours, currentTime = start.plusHours(6))
        val right = labels.first { it.side == "RIGHT" }
        assertTrue("Right label should be marked isToday", right.isToday)
    }

    @Test
    fun tempGraph_pastWindow_neitherLabelIsToday() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val hours = makeHours(start, count = 24)
        val labels = renderTemp(hours)
        assertFalse("No label should be today in a past window", labels.any { it.isToday })
    }

    @Test
    fun tempGraph_leftLabelXIsNearLeftEdge() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val hours = makeHours(start, count = 24)
        val labels = renderTemp(hours, widthPx = 800)
        val left = labels.first { it.side == "LEFT" }
        assertTrue("Left label x should be in the left quarter of the graph", left.x < 200f)
    }

    @Test
    fun tempGraph_rightLabelXIsNearRightEdge() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val hours = makeHours(start, count = 24)
        val labels = renderTemp(hours, widthPx = 800)
        val right = labels.first { it.side == "RIGHT" }
        assertTrue("Right label x should be in the right quarter of the graph", right.x > 600f)
    }

    // ── Rain graph tests ──────────────────────────────────────────────────────

    @Test
    fun precipGraph_alwaysProducesTwoLabels() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val hours = makePrecipHours(start, count = 24)
        val labels = renderPrecip(hours)
        assertEquals("Expected exactly 2 day labels", 2, labels.size)
    }

    @Test
    fun precipGraph_leftAndRightSidesPresent() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val hours = makePrecipHours(start, count = 24)
        val labels = renderPrecip(hours)
        assertTrue("Should have LEFT label",  labels.any { it.side == "LEFT" })
        assertTrue("Should have RIGHT label", labels.any { it.side == "RIGHT" })
    }

    @Test
    fun precipGraph_todayOnLeft_markedIsToday() {
        val today = LocalDate.now()
        val start = today.atTime(8, 0)
        val hours = makePrecipHours(start, count = 24)
        val labels = renderPrecip(hours, currentTime = start.plusHours(4))
        val left = labels.first { it.side == "LEFT" }
        assertTrue("Left label should be marked isToday", left.isToday)
    }

    @Test
    fun precipGraph_pastWindow_neitherLabelIsToday() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val hours = makePrecipHours(start, count = 24)
        val labels = renderPrecip(hours)
        assertFalse("No label should be today in a past window", labels.any { it.isToday })
    }

    @Test
    fun precipGraph_matchesTempGraph_forSameInput() {
        // Both renderers should agree on left/right dates for equivalent input
        val start = LocalDateTime.of(2025, 6, 14, 20, 0)
        val currentTime = start.plusHours(6)
        val tempHours   = makeHours(start, count = 13)
        val precipHours = makePrecipHours(start, count = 13)

        val tempLabels   = renderTemp(tempHours, currentTime = currentTime)
        val precipLabels = renderPrecip(precipHours, currentTime = currentTime)

        val tempLeft   = tempLabels.first   { it.side == "LEFT" }
        val precipLeft = precipLabels.first { it.side == "LEFT" }
        assertEquals("Left date should match between renderers",  tempLeft.date,    precipLeft.date)
        assertEquals("Left text should match between renderers",  tempLeft.dayText, precipLeft.dayText)

        val tempRight   = tempLabels.first   { it.side == "RIGHT" }
        val precipRight = precipLabels.first { it.side == "RIGHT" }
        assertEquals("Right date should match between renderers", tempRight.date,    precipRight.date)
        assertEquals("Right text should match between renderers", tempRight.dayText, precipRight.dayText)
    }

    // ── midnight hour label regression ────────────────────────────────────────

    @Test
    fun hourData_midnightLabel_doesNotContainDayName() {
        // Regression: formatHourLabel used to produce "Sun 12a"; should now be "12a"
        val midnight = LocalDateTime.of(2025, 6, 15, 0, 0)
        val hours = makeHours(midnight, count = 1)
        assertEquals("Midnight hour label should be '12a', not 'Sun 12a'", "12a", hours[0].label)
    }
}
