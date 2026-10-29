package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import kotlin.math.roundToInt

@Category(ShortDuration::class)
class DailyForecastGraphRendererTest {

    @Test
    fun `input normalizer removes every invalid temperature slot`() {
        val date = LocalDate.of(2026, 7, 30)
        val result =
            DailyGraphInputNormalizer.normalize(
                days =
                    listOf(
                        DailyForecastGraphRenderer.DayData(
                            date = date,
                            label = "Thu",
                            solidLineHigh = Float.NaN,
                            solidLineLow = Float.POSITIVE_INFINITY,
                            bottomStackLow = Float.NEGATIVE_INFINITY,
                            dashedLineHigh = Float.NaN,
                            dashedLineLow = Float.POSITIVE_INFINITY,
                            snapshotHigh = Float.NEGATIVE_INFINITY,
                            snapshotLow = Float.NaN,
                            ghostLineHigh = Float.POSITIVE_INFINITY,
                        ),
                    ),
                columns = 1,
            )

        val day = result.days.single().day
        assertNull(day.solidLineHigh)
        assertNull(day.solidLineLow)
        assertNull(day.bottomStackLow)
        assertNull(day.dashedLineHigh)
        assertNull(day.dashedLineLow)
        assertNull(day.snapshotHigh)
        assertNull(day.snapshotLow)
        assertNull(day.ghostLineHigh)
        assertEquals(8, result.rejectedTemperatures.size)
    }

    @Test
    fun `input normalizer keeps valid cold temperatures`() {
        val day =
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 1, 15),
                label = "Thu",
                solidLineHigh = -60f,
                solidLineLow = -80f,
            )

        val normalized = DailyGraphInputNormalizer.normalize(listOf(day), columns = 1)

        assertEquals(-60f, normalized.days.single().day.solidLineHigh!!, 0f)
        assertEquals(-80f, normalized.days.single().day.solidLineLow!!, 0f)
        assertTrue(normalized.rejectedTemperatures.isEmpty())
    }

    @Test
    fun `input normalizer retains first day when resolved columns collide`() {
        val firstDate = LocalDate.of(2026, 7, 30)
        val result =
            DailyGraphInputNormalizer.normalize(
                days =
                    listOf(
                        DailyForecastGraphRenderer.DayData(
                            date = firstDate,
                            label = "Thu",
                            solidLineHigh = 70f,
                            solidLineLow = 50f,
                            columnIndex = 2,
                        ),
                        DailyForecastGraphRenderer.DayData(
                            date = firstDate.plusDays(1),
                            label = "Fri",
                            solidLineHigh = 71f,
                            solidLineLow = 51f,
                            columnIndex = 99,
                        ),
                    ),
                columns = 3,
            )

        assertEquals(listOf(firstDate), result.days.map { it.day.date })
        assertEquals(1, result.columnCollisions.size)
        assertEquals(firstDate.plusDays(1), result.columnCollisions.single().skippedDate)
    }

    @Test
    fun `formatTempLabel rounds to integer when forceInteger`() {
        assertEquals("49°", DailyTemperatureLabelRenderer.format(48.6f, forceInteger = true, useCelsius = false))
    }

    @Test
    fun `formatTempLabel preserves decimal for fractional part by default`() {
        assertEquals("48.6°", DailyTemperatureLabelRenderer.format(48.6f, useCelsius = false))
    }

    @Test
    fun `formatTempLabel suppresses the point-zero case`() {
        assertEquals("49°", DailyTemperatureLabelRenderer.format(49.0f, useCelsius = false))
    }

    @Test
    fun `fitScaleForWidth leaves a label that already fits unchanged`() {
        // "78°" comfortably inside the column: no shrink.
        assertEquals(1f, DailyTemperatureLabelRenderer.fitScaleForWidth(40f, maxWidthPx = 60f, currentScale = 1f))
    }

    @Test
    fun `fitScaleForWidth shrinks an overflowing label to fit the column`() {
        // 80px label, 60px budget -> scale down to 0.75 so it fits.
        assertEquals(0.75f, DailyTemperatureLabelRenderer.fitScaleForWidth(80f, maxWidthPx = 60f, currentScale = 1f), 0.0001f)
    }

    @Test
    fun `fitScaleForWidth never shrinks below the legibility floor`() {
        // Wildly oversized label clamps to currentScale * minScale (legibility floor), not to 0.
        val minScale = 0.7f
        assertEquals(
            0.7f,
            DailyTemperatureLabelRenderer.fitScaleForWidth(1000f, maxWidthPx = 10f, currentScale = 1f, minScale = minScale),
            0.0001f,
        )
    }

    @Test
    fun `fitScaleForWidth composes with an existing wide-label scale`() {
        // Starting from a wide-label scale step (here 0.95 as a stand-alone example), an
        // 80px@0.95 label in a 60px column shrinks further to 0.95 * 0.75 (still above the
        // 0.95 * 0.7 floor).
        val current = 0.95f
        val result = DailyTemperatureLabelRenderer.fitScaleForWidth(80f, maxWidthPx = 60f, currentScale = current)
        assertEquals(current * (60f / 80f), result, 0.0001f)
    }

    @Test
    fun `resolveBottomStackLow prefers explicit bottom stack low over observed low`() {
        val day = DailyForecastGraphRenderer.DayData(
            date = LocalDate.of(2030, 6, 15),
            label = "Today",
            solidLineHigh = 74f,
            solidLineLow = 67f,
            bottomStackLow = 65f,
            dashedLineLow = 65f,
            isToday = true,
        )

        assertEquals(65f, DailyColumnRenderer.resolveBottomStackLow(day)!!, 0.1f)
    }

    @Test
    fun `resolveBottomStackLow falls back to observed low when explicit anchor is absent`() {
        val day = DailyForecastGraphRenderer.DayData(
            date = LocalDate.of(2030, 6, 16),
            label = "Sun",
            solidLineHigh = 76f,
            solidLineLow = 58f,
        )

        assertEquals(58f, DailyColumnRenderer.resolveBottomStackLow(day)!!, 0.1f)
    }

}
