package com.weatherwidget.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalTime

@Category(ShortDuration::class)
class NavigationUtilsTest {

    @Test
    fun `getDayOffsets returns correct sizes for all column counts`() {
        assertEquals("1 column", 1, NavigationUtils.getDayOffsets(1).size)
        assertEquals("2 columns", 2, NavigationUtils.getDayOffsets(2).size)
        assertEquals("3 columns", 3, NavigationUtils.getDayOffsets(3).size)
        assertEquals("5 columns", 5, NavigationUtils.getDayOffsets(5).size)
        assertEquals("7 columns", 7, NavigationUtils.getDayOffsets(7).size)
        assertEquals("9 columns", 9, NavigationUtils.getDayOffsets(9).size)
    }

    @Test
    fun `getDayOffsets starts from -1 when not skipping history`() {
        val offsets = NavigationUtils.getDayOffsets(7, skipHistory = false)
        assertEquals("Start offset should be -1", -1L, offsets.first())
        assertEquals("End offset should be 5", 5L, offsets.last())
    }

    @Test
    fun `getDayOffsets starts from 0 when skipping history`() {
        val offsets = NavigationUtils.getDayOffsets(7, skipHistory = true)
        assertEquals("Start offset should be 0", 0L, offsets.first())
        assertEquals("End offset should be 6", 6L, offsets.last())
    }

    @Test
    fun `getDayOffsets always starts from 0 for narrow widgets`() {
        val offsets2 = NavigationUtils.getDayOffsets(2, skipHistory = false)
        assertEquals("2-col start offset should be 0", 0L, offsets2.first())

        val offsets1 = NavigationUtils.getDayOffsets(1, skipHistory = false)
        assertEquals("1-col start offset should be 0", 0L, offsets1.first())
    }

    @Test
    fun `getDisplayCenterDate shift for skipYesterday`() {
        val today = LocalDate.of(2030, 6, 15)

        // Offset 0 with skipYesterday does NOT shift center (skipHistory handles offset 0).
        val center0 = NavigationUtils.getDisplayCenterDate(today, 0, skipYesterday = true)
        assertEquals("Offset 0 skipYesterday should be today", today, center0)

        // Offset 1 with skipYesterday SHIFTS center by +1 to keep one-day steps.
        val center1 = NavigationUtils.getDisplayCenterDate(today, 1, skipYesterday = true)
        assertEquals("Offset 1 skipYesterday should be today+2", today.plusDays(2), center1)

        val centerNeg1 = NavigationUtils.getDisplayCenterDate(today, -1, skipYesterday = true)
        assertEquals("Offset -1 skipYesterday should be today", today, centerNeg1)
    }

    @Test
    fun `shouldSkipYesterday uses 8am threshold for narrow widgets`() {
        val eightAm = LocalTime.of(8, 0)
        val sevenFiftyNine = LocalTime.of(7, 59)

        assertTrue("8 cols at 8am should skip yesterday",
            NavigationUtils.shouldSkipYesterday(eightAm, numColumns = 8))
        assertFalse("8 cols at 7:59am should not skip yesterday",
            NavigationUtils.shouldSkipYesterday(sevenFiftyNine, numColumns = 8))
        assertTrue("1 col at 8am should skip yesterday",
            NavigationUtils.shouldSkipYesterday(eightAm, numColumns = 1))
    }

    @Test
    fun `shouldSkipYesterday wide widgets never skip yesterday early`() {
        val nineAm = LocalTime.of(9, 0)
        val fivePm = LocalTime.of(17, 0)
        val sixPm = LocalTime.of(18, 0)
        val elevenPm = LocalTime.of(23, 0)

        assertFalse("9 cols at 9am should not skip yesterday",
            NavigationUtils.shouldSkipYesterday(nineAm, numColumns = 9))
        assertFalse("9 cols at 5pm should not skip yesterday",
            NavigationUtils.shouldSkipYesterday(fivePm, numColumns = 9))
        assertFalse("9 cols at 6pm should not skip yesterday",
            NavigationUtils.shouldSkipYesterday(sixPm, numColumns = 9))
        assertFalse("9 cols at 11pm should not skip yesterday",
            NavigationUtils.shouldSkipYesterday(elevenPm, numColumns = 9))
        assertFalse("Default numColumns at 6pm should not skip yesterday",
            NavigationUtils.shouldSkipYesterday(sixPm))
    }

    // --- isTodayOrYesterdayInRange: the observations-button gate ---

    @Test
    fun `observations button shows when today is in range`() {
        val today = LocalDate.of(2026, 8, 12)
        assertTrue(
            NavigationUtils.isTodayOrYesterdayInRange(today, today, today.plusDays(3)),
        )
    }

    @Test
    fun `observations button shows when only yesterday is in range`() {
        val today = LocalDate.of(2026, 8, 12)
        // Panned back so today has scrolled off the right edge but yesterday still shows.
        assertTrue(
            NavigationUtils.isTodayOrYesterdayInRange(today, today.minusDays(3), today.minusDays(1)),
        )
    }

    @Test
    fun `observations button drops when neither today nor yesterday is in range`() {
        val today = LocalDate.of(2026, 8, 12)
        // Fully navigated into the future: window starts the day after today.
        assertFalse(
            NavigationUtils.isTodayOrYesterdayInRange(today, today.plusDays(1), today.plusDays(5)),
        )
        // Fully navigated into the past: window ends the day before yesterday.
        assertFalse(
            NavigationUtils.isTodayOrYesterdayInRange(today, today.minusDays(6), today.minusDays(2)),
        )
    }

    @Test
    fun `observations button respects the range boundaries inclusively`() {
        val today = LocalDate.of(2026, 8, 12)
        // yesterday as the leftmost column still counts.
        assertTrue(
            NavigationUtils.isTodayOrYesterdayInRange(today, today.minusDays(1), today.minusDays(1)),
        )
        // today as the rightmost column still counts.
        assertTrue(
            NavigationUtils.isTodayOrYesterdayInRange(today, today.minusDays(1), today),
        )
    }

    // --- dailyLoadWindow: the query window must track the render, never a flat constant ---

    @Test
    fun `dailyLoadWindow matches the rendered range for a spread of widths and offsets`() {
        // The contract that matters: whatever getVisibleDateRange draws, dailyLoadWindow covers.
        // Asserted against getVisibleDateRange itself so the two can never drift apart — a flat
        // 30-day window both over-fetched ~3x for a 10-column widget AND under-fetched at 7,
        // leaving the today+8 column with no row at all.
        val today = LocalDate.of(2026, 8, 3)
        val headroom = 1L
        for (cols in intArrayOf(1, 2, 3, 6, 7, 10, 14, 22)) {
            for (offset in intArrayOf(-5, -1, 0, 1, 5, 12)) {
                for (skipYesterday in booleanArrayOf(false, true)) {
                    val window =
                        NavigationUtils.dailyLoadWindow(today, offset, cols, skipYesterday, headroom)
                    val (leftmost, rightmost) =
                        NavigationUtils.getVisibleDateRange(today, offset, cols, skipYesterday)
                    val label = "cols=$cols offset=$offset skipYesterday=$skipYesterday"
                    assertTrue(
                        "$label: history window must reach the leftmost rendered column ($leftmost)",
                        !today.minusDays(window.historyDays).isAfter(leftmost),
                    )
                    assertTrue(
                        "$label: forecast window must reach the rightmost rendered column ($rightmost)",
                        !today.plusDays(window.forecastDays).isBefore(rightmost),
                    )
                }
            }
        }
    }

    @Test
    fun `dailyLoadWindow for the Fold 10-column widget at offset 0 needs today plus 9 not 30`() {
        // Regression anchor for the over-fetch: this widget renders yesterday..today+8, so with one
        // day of headroom the query needs today+9 — not the flat 30 that was there before.
        val window =
            NavigationUtils.dailyLoadWindow(
                today = LocalDate.of(2026, 8, 3),
                dateOffset = 0,
                numColumns = 10,
                skipYesterday = false,
            )
        assertEquals("forecast days", 9L, window.forecastDays)
        assertEquals("history days", 2L, window.historyDays)
    }

    @Test
    fun `dailyLoadWindow never returns a negative span`() {
        // Narrow widgets skip yesterday, so the leftmost column is today and history need is 0+headroom.
        val window =
            NavigationUtils.dailyLoadWindow(
                today = LocalDate.of(2026, 8, 3),
                dateOffset = 0,
                numColumns = 1,
                skipYesterday = true,
                headroomDays = 0L,
            )
        assertTrue("history must not be negative", window.historyDays >= 0L)
        assertTrue("forecast must not be negative", window.forecastDays >= 0L)
    }

    @Test
    fun `coerceAtLeast takes the widest of two windows per side`() {
        val narrow = NavigationUtils.DailyLoadWindow(historyDays = 2L, forecastDays = 4L)
        val wide = NavigationUtils.DailyLoadWindow(historyDays = 7L, forecastDays = 3L)
        val merged = narrow.coerceAtLeast(wide)
        assertEquals(7L, merged.historyDays)
        assertEquals(4L, merged.forecastDays)
    }
}
