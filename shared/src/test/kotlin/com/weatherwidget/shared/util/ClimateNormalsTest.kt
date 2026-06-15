package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay

class ClimateNormalsTest {

    private fun day(date: String, high: Float, low: Float) =
        DailyForecast(date = date, highTemp = high, lowTemp = low, condition = "")

    @Test
    fun `monthlyMeans groups by month and averages to one decimal`() {
        val daily = listOf(
            day("2020-06-10", 76f, 54f),
            day("2020-06-20", 77f, 55f), // June mean -> 76.5 / 54.5
            day("2020-07-15", 75f, 57f),
            day("2020-08-15", 80f, 60f),
        )
        val (high, low) = ClimateNormals.monthlyMeans(daily)
        assertEquals(76.5f, high[6]!!, 0.001f) // fractional mean preserved
        assertEquals(54.5f, low[6]!!, 0.001f)
        assertEquals(75f, high[7]!!, 0.001f)
        assertEquals(80f, high[8]!!, 0.001f)
    }

    @Test
    fun `monthlyMeans ignores NaN`() {
        val daily = listOf(
            day("2020-06-10", Float.NaN, 54f),
            day("2020-06-20", 78f, Float.NaN),
        )
        val (high, low) = ClimateNormals.monthlyMeans(daily)
        assertEquals(78f, high[6]!!, 0.001f) // only the non-NaN high counted
        assertEquals(54f, low[6]!!, 0.001f)
    }

    private fun fullYear(): Pair<Map<Int, Float>, Map<Int, Float>> {
        // Distinct per-month highs/lows: high = month*5+40, low = high-20.
        val high = (1..12).associateWith { (it * 5 + 40).toFloat() }
        val low = (1..12).associateWith { (it * 5 + 20).toFloat() }
        return high to low
    }

    @Test
    fun `expandMonthlyToDaily returns the month mean at the 15th`() {
        val (high, low) = fullYear()
        val result = ClimateNormals.expandMonthlyToDaily(high, low)
        assertEquals(high[7]!!, result[MonthDay.of(7, 15)]!!.first, 0.001f)
        assertEquals(low[7]!!, result[MonthDay.of(7, 15)]!!.second, 0.001f)
    }

    @Test
    fun `expandMonthlyToDaily interpolates between month midpoints`() {
        val (high, low) = fullYear()
        val result = ClimateNormals.expandMonthlyToDaily(high, low)
        val augFirst = result[MonthDay.of(8, 1)]!!.first
        assertTrue("between July ${high[7]} and Aug ${high[8]}", augFirst > high[7]!! && augFirst < high[8]!!)
    }

    @Test
    fun `expandMonthlyToDaily covers Feb 29`() {
        val (high, low) = fullYear()
        assertNotNull(ClimateNormals.expandMonthlyToDaily(high, low)[MonthDay.of(2, 29)])
    }

    @Test
    fun `rollingWindow is 20 complete years ending last year`() {
        val (start, end) = ClimateNormals.rollingWindow(LocalDate.of(2026, 6, 14))
        assertEquals("2006-01-01", start)
        assertEquals("2025-12-31", end)
    }

    @Test
    fun `locationKey rounds to one tenth`() {
        assertEquals("37.4_-122.1", ClimateNormals.locationKey(37.4244, -122.1428))
    }
}
