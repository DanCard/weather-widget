package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class PartialForecastDaysTest {
    private data class Row(val high: Float?, val low: Float?, val fetchedAt: Long)
    private val today = LocalDate.parse("2026-10-02")

    @Test
    fun `complete replacement takes the newest row with both values`() {
        val pick = PartialForecastDays.completeReplacement(
            listOf(Row(91f, 62f, 1), Row(92f, null, 3), Row(90f, 61f, 2)),
            { it.high }, { it.low }, { it.fetchedAt },
        )
        assertEquals(Row(90f, 61f, 2), pick)
        assertNull(PartialForecastDays.completeReplacement(listOf(Row(92f, null, 3)), { it.high }, { it.low }, { it.fetchedAt }))
    }

    private fun todayRow(batch: Row?, stored: List<Row>) =
        PartialForecastDays.todayRow(batch, stored, { it.high }, { it.low }, { it.fetchedAt })

    @Test
    fun `today row - a complete batch row wins over any stored row`() {
        assertEquals(Row(92f, 68f, 5), todayRow(Row(92f, 68f, 5), listOf(Row(91f, 67f, 9))))
    }

    @Test
    fun `today row - a partial batch row yields to the newest complete stored row`() {
        // NWS / Open-Meteo in the evening: the batch keeps today's high but has no low.
        assertEquals(
            Row(90f, 67f, 4),
            todayRow(Row(92f, null, 9), listOf(Row(91f, 68f, 2), Row(90f, 67f, 4), Row(89f, null, 8))),
        )
    }

    @Test
    fun `today row - a complete stored row more than a day older than the newest does not replace it`() {
        // 2026-10-09 Pixel: the site's only complete Open-Meteo row for Oct 9 was fetched Oct 2
        // (90/74); that day's fetch said 69.5 with no low. The week-old row drew as today's forecast.
        val day = 86_400_000L
        val oct2 = Row(90f, 74f, 0)
        val oct9 = Row(69.5f, null, 7 * day)
        assertEquals(oct9, todayRow(oct9, listOf(oct2, oct9)))
        assertEquals(oct9, todayRow(null, listOf(oct2, oct9)))
        // Within the window the morning's complete row still fills the evening's missing low.
        val morning = Row(70f, 56f, 7 * day - 10 * 3_600_000L)
        assertEquals(morning, todayRow(oct9, listOf(oct2, morning, oct9)))
    }

    @Test
    fun `today row - a batch with no row for today still finds the stored complete row`() {
        // Silurian after 17:00 PDT: the batch starts at tomorrow. Desktop drew the climate normal here.
        assertEquals(
            Row(90.1f, 67.4f, 437),
            todayRow(null, listOf(Row(90.1f, 67.4f, 437), Row(89.6f, null, 1408), Row(91f, 68.2f, 2250 - 2400))),
        )
    }

    @Test
    fun `today row - with no complete row the newest one-sided row stands, batch row first`() {
        assertEquals(Row(92f, null, 3), todayRow(Row(92f, null, 3), listOf(Row(89f, null, 7))))
        assertEquals(Row(89f, null, 7), todayRow(null, listOf(Row(88f, null, 2), Row(89f, null, 7), Row(null, null, 9))))
    }

    @Test
    fun `today row - nothing stored leaves today empty for the climate normal`() {
        assertNull(todayRow(null, emptyList()))
        assertNull(todayRow(null, listOf(Row(null, null, 1))))
    }

    @Test
    fun `today range falls back to the hourly extremes only for the missing side`() {
        assertEquals(92f to 58f, PartialForecastDays.todayForecastRange(92f, null, listOf(60f, 58f, 90f)))
        assertEquals(92f to 61f, PartialForecastDays.todayForecastRange(92f, 61f, listOf(60f, 58f, 95f)))
        assertEquals(null to null, PartialForecastDays.todayForecastRange(null, null, emptyList()))
    }

    @Test
    fun `terminal low-only NWS day is only the last NWS future day`() {
        val last = today.plusDays(7)
        assertTrue(PartialForecastDays.isTerminalLowOnlyNwsFutureDay("NWS", null, 58f, last, today, last))
        assertFalse(PartialForecastDays.isTerminalLowOnlyNwsFutureDay("NWS", null, 58f, today.plusDays(3), today, last))
        assertFalse(PartialForecastDays.isTerminalLowOnlyNwsFutureDay("OPEN_METEO", null, 58f, last, today, last))
        assertFalse(PartialForecastDays.isTerminalLowOnlyNwsFutureDay("NWS", 80f, 58f, last, today, last))
    }

    @Test
    fun `a partial future day takes the climate normal, unless it is the terminal NWS day`() {
        assertEquals(
            PartialForecastDays.FutureValues(75f, 55f, isClimateOverlay = true),
            PartialForecastDays.futureFromNormals(null, 58f, isTerminalLowOnlyNws = false, normal = 75f to 55f),
        )
        assertEquals(
            PartialForecastDays.FutureValues(null, 58f, isClimateOverlay = false),
            PartialForecastDays.futureFromNormals(null, 58f, isTerminalLowOnlyNws = true, normal = 75f to 55f),
        )
        assertEquals(
            PartialForecastDays.FutureValues(80f, 60f, isClimateOverlay = false),
            PartialForecastDays.futureFromNormals(80f, 60f, isTerminalLowOnlyNws = false, normal = 75f to 55f),
        )
        assertEquals(
            PartialForecastDays.FutureValues(null, 58f, isClimateOverlay = false),
            PartialForecastDays.futureFromNormals(null, 58f, isTerminalLowOnlyNws = false, normal = null),
        )
    }
}
