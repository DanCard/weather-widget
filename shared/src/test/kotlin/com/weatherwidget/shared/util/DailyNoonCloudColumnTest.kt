package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/** [DailyNoonCloudCover.forColumn]: one rule for a daily column's noon cloud on both platforms. */
@Category(ShortDuration::class)
class DailyNoonCloudColumnTest {

    private fun column(
        isPast: Boolean = false,
        frozen: Int? = null,
        row: Int? = null,
        displaySource: Boolean = true,
        climateNormal: Boolean = false,
        hourly: Int? = null,
    ) = DailyNoonCloudCover.forColumn(isPast, frozen, row, displaySource, climateNormal) { hourly }

    @Test
    fun pastDayTakesTheFrozenValueThenHourly() {
        assertEquals(40, column(isPast = true, frozen = 40, row = 90, hourly = 10))
        // A past day never reads the forecast row.
        assertEquals(10, column(isPast = true, frozen = null, row = 90, hourly = 10))
    }

    @Test
    fun todayTakesTheRowThenHourly() {
        assertEquals(91, column(row = 91, hourly = 5))
        assertEquals(5, column(row = null, hourly = 5))
        assertNull(column())
    }

    /** Today matches the hourly graph's 12:00 point first; the row only fills a missing hourly noon. */
    @Test
    fun todayTakesHourlyThenTheRowWithOneScan() {
        var scans = 0
        fun today(row: Int?, hourly: Int?, climateNormal: Boolean = false) =
            DailyNoonCloudCover.forColumn(false, null, row, true, climateNormal, isToday = true) { scans++; hourly }

        assertEquals(5, today(row = 91, hourly = 5))
        assertEquals(91, today(row = 91, hourly = null))
        assertNull(today(row = 91, hourly = null, climateNormal = true))
        assertEquals(3, scans)
    }

    @Test
    fun rowFromAnotherSourceIsSkipped() {
        // Android's gate (2026-10-23 audit): a row of another source must not set this column's sky.
        assertEquals(5, column(row = 91, displaySource = false, hourly = 5))
    }

    @Test
    fun climateNormalRowIsSkipped() {
        // Desktop's gate: a climate-normal stand-in has no sky of its own.
        assertEquals(5, column(row = 91, climateNormal = true, hourly = 5))
    }
}
