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
}
