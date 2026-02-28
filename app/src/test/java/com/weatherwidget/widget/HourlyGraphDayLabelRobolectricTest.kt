package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Robolectric tests for day label placement on the hourly temperature and rain graphs.
 *
 * Runs on JVM with real Android Paint/Canvas via Robolectric, verifying
 * label candidate selection and today-highlighting through onDayLabelPlaced callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HourlyGraphDayLabelRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    // ── Candidate selection ───────────────────────────────────────────────────

    @Test
    fun `temp graph produces exactly two day labels`() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val labels = renderTemp(makeHours(start, 24))
        assertEquals(2, labels.size)
    }

    @Test
    fun `temp graph left label date matches first hour`() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val labels = renderTemp(makeHours(start, 24))
        assertEquals(start.toLocalDate(), labels.first { it.side == "LEFT" }.date)
    }

    @Test
    fun `temp graph right label date matches last hour`() {
        val start = LocalDateTime.of(2025, 6, 14, 20, 0)
        val hours = makeHours(start, 13) // ends June 15
        val labels = renderTemp(hours)
        assertEquals(start.plusHours(12).toLocalDate(), labels.first { it.side == "RIGHT" }.date)
    }

    @Test
    fun `temp graph same-day window both labels have same date`() {
        val start = LocalDateTime.of(2025, 6, 15, 4, 0)
        val labels = renderTemp(makeHours(start, 5))
        val left  = labels.first { it.side == "LEFT" }
        val right = labels.first { it.side == "RIGHT" }
        assertEquals(left.date, right.date)
    }

    @Test
    fun `temp graph window spanning midnight has different left and right dates`() {
        val start = LocalDateTime.of(2025, 6, 14, 20, 0)
        val labels = renderTemp(makeHours(start, 13))
        val left  = labels.first { it.side == "LEFT" }
        val right = labels.first { it.side == "RIGHT" }
        assertFalse(left.date == right.date)
    }

    // ── Today highlighting ────────────────────────────────────────────────────

    @Test
    fun `temp graph today on left is marked isToday`() {
        val today = LocalDate.now()
        val start = today.atTime(8, 0)
        val labels = renderTemp(makeHours(start, 24), currentTime = start.plusHours(4))
        assertTrue(labels.first { it.side == "LEFT" }.isToday)
    }

    @Test
    fun `temp graph past date on left is not isToday`() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val labels = renderTemp(makeHours(start, 24))
        assertFalse(labels.first { it.side == "LEFT" }.isToday)
    }

    @Test
    fun `temp graph today on right is marked isToday`() {
        val today = LocalDate.now()
        val start = today.minusDays(1).atTime(20, 0)
        val labels = renderTemp(makeHours(start, 13), currentTime = start.plusHours(6))
        assertTrue(labels.first { it.side == "RIGHT" }.isToday)
    }

    @Test
    fun `temp graph past window neither label is isToday`() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val labels = renderTemp(makeHours(start, 24))
        assertFalse(labels.any { it.isToday })
    }

    // ── Rain graph ────────────────────────────────────────────────────────────

    @Test
    fun `precip graph produces exactly two day labels`() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val labels = renderPrecip(makePrecipHours(start, 24))
        assertEquals(2, labels.size)
    }

    @Test
    fun `precip graph today on left is marked isToday`() {
        val today = LocalDate.now()
        val start = today.atTime(8, 0)
        val labels = renderPrecip(makePrecipHours(start, 24), currentTime = start.plusHours(4))
        assertTrue(labels.first { it.side == "LEFT" }.isToday)
    }

    @Test
    fun `precip graph past window neither label is isToday`() {
        val start = LocalDateTime.of(2025, 6, 14, 8, 0)
        val labels = renderPrecip(makePrecipHours(start, 24))
        assertFalse(labels.any { it.isToday })
    }

    @Test
    fun `precip and temp graphs agree on dates for same input`() {
        val start = LocalDateTime.of(2025, 6, 14, 20, 0)
        val currentTime = start.plusHours(6)
        val tempLabels   = renderTemp(makeHours(start, 13), currentTime = currentTime)
        val precipLabels = renderPrecip(makePrecipHours(start, 13), currentTime = currentTime)

        assertEquals(
            tempLabels.first { it.side == "LEFT" }.date,
            precipLabels.first { it.side == "LEFT" }.date,
        )
        assertEquals(
            tempLabels.first { it.side == "RIGHT" }.date,
            precipLabels.first { it.side == "RIGHT" }.date,
        )
    }
}
