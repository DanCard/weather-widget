package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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

    @Test
    fun `fillGaps skips covered dates`() {
        val today = LocalDate.of(2026, 3, 1)
        val normals = mapOf(MonthDay.of(3, 1) to (60f to 40f), MonthDay.of(3, 2) to (61f to 41f))
        val covered = setOf(LocalDate.of(2026, 3, 1))

        val gaps = ClimateNormals.fillGaps(covered, normals, today, horizonDays = 1)

        assertEquals(listOf(LocalDate.of(2026, 3, 2)), gaps.map { it.date })
        assertEquals(61f, gaps.single().highTemp, 0.001f)
        assertEquals(41f, gaps.single().lowTemp, 0.001f)
    }

    @Test
    fun `fillGaps horizon bounds are inclusive`() {
        val today = LocalDate.of(2026, 3, 1)
        val normals = (1..3).associate { MonthDay.of(3, it) to (60f to 40f) }

        val gaps = ClimateNormals.fillGaps(emptySet(), normals, today, horizonDays = 2)

        assertEquals(
            listOf(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3)),
            gaps.map { it.date },
        )
    }

    @Test
    fun `fillGaps returns empty for empty normals`() {
        val gaps = ClimateNormals.fillGaps(emptySet(), emptyMap(), LocalDate.of(2026, 3, 1), horizonDays = 5)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `fillGaps skips a date with no normal for its MonthDay`() {
        val today = LocalDate.of(2026, 3, 1)
        val normals = mapOf(MonthDay.of(3, 1) to (60f to 40f)) // no entry for 3/2

        val gaps = ClimateNormals.fillGaps(emptySet(), normals, today, horizonDays = 1)

        assertEquals(listOf(LocalDate.of(2026, 3, 1)), gaps.map { it.date })
    }

    @Test
    fun `fillGaps with no covered dates fills today through horizon`() {
        val today = LocalDate.of(2026, 3, 1)
        val normals = (1..5).associate { MonthDay.of(3, it) to (60f to 40f) }

        val gaps = ClimateNormals.fillGaps(emptySet(), normals, today, horizonDays = 4)

        assertEquals((1..5).map { LocalDate.of(2026, 3, it) }, gaps.map { it.date })
    }
}
