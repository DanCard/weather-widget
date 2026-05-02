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
    fun `getDisplayCenterDate shift for evening mode`() {
        val today = LocalDate.of(2030, 6, 15)
        
        // Offset 0 in evening mode does NOT shift center (it uses skipHistory instead)
        val center0 = NavigationUtils.getDisplayCenterDate(today, 0, isEveningMode = true)
        assertEquals("Offset 0 evening should be today", today, center0)
        
        // Offset 1 in evening mode SHIFTS center by +1 to maintain 1-day step
        val center1 = NavigationUtils.getDisplayCenterDate(today, 1, isEveningMode = true)
        assertEquals("Offset 1 evening should be today+2", today.plusDays(2), center1)

        val centerNeg1 = NavigationUtils.getDisplayCenterDate(today, -1, isEveningMode = true)
        assertEquals("Offset -1 evening should be today", today, centerNeg1)
    }

    @Test
    fun `isEveningMode uses 5pm threshold for narrow widgets`() {
        val fivePm = LocalTime.of(17, 0)
        val fourFiftyNine = LocalTime.of(16, 59)
        val sixPm = LocalTime.of(18, 0)

        assertTrue("8 cols at 5pm should be evening mode",
            NavigationUtils.isEveningMode(fivePm, numColumns = 8))
        assertFalse("8 cols at 4:59pm should not be evening mode",
            NavigationUtils.isEveningMode(fourFiftyNine, numColumns = 8))
        assertTrue("1 col at 5pm should be evening mode",
            NavigationUtils.isEveningMode(fivePm, numColumns = 1))
    }

    @Test
    fun `isEveningMode uses 6pm threshold for wide widgets`() {
        val fivePm = LocalTime.of(17, 0)
        val sixPm = LocalTime.of(18, 0)
        val elevenPm = LocalTime.of(23, 0)

        assertFalse("9 cols at 5pm should not be evening mode",
            NavigationUtils.isEveningMode(fivePm, numColumns = 9))
        assertFalse("9 cols at 6pm should not be evening mode",
            NavigationUtils.isEveningMode(sixPm, numColumns = 9))
        assertFalse("9 cols at 11pm should not be evening mode",
            NavigationUtils.isEveningMode(elevenPm, numColumns = 9))
        assertFalse("Default numColumns at 6pm should not be evening mode",
            NavigationUtils.isEveningMode(sixPm))
    }
}
