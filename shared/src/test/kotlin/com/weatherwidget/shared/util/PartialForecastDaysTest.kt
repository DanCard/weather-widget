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
