package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.util.NavigationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class DailyLargeTodayOverlayPolicyTest {
    @Test
    fun `ten by four daily graph with Today uses nine dates`() {
        val decision =
            DailyLargeTodayOverlayPolicy.resolve(
                launcherColumns = 10,
                launcherRows = 4,
                useGraph = true,
                todayVisible = true,
            )

        assertTrue(decision.enabled)
        assertEquals(9, decision.displayColumns)
    }

    @Test
    fun `gate preserves layouts below either size threshold or without Today`() {
        val decisions =
            listOf(
                DailyLargeTodayOverlayPolicy.resolve(10, 3, useGraph = true, todayVisible = true),
                DailyLargeTodayOverlayPolicy.resolve(9, 5, useGraph = true, todayVisible = true),
                DailyLargeTodayOverlayPolicy.resolve(10, 5, useGraph = false, todayVisible = true),
                DailyLargeTodayOverlayPolicy.resolve(10, 5, useGraph = true, todayVisible = false),
            )

        assertTrue(decisions.all { !it.enabled })
        assertEquals(listOf(10, 9, 10, 10), decisions.map { it.displayColumns })
    }

    @Test
    fun `Today consumes two visual slots while other dates consume one`() {
        val slots =
            DailyLargeTodayOverlayPolicy.slots(
                todayFlags = listOf(false, true, false, false, false, false, false, false, false),
                enabled = true,
            )

        assertEquals(9, slots.size)
        assertEquals(DailyLargeTodayOverlayPolicy.Slot(0, 1), slots[0])
        assertEquals(DailyLargeTodayOverlayPolicy.Slot(1, 2), slots[1])
        assertEquals(DailyLargeTodayOverlayPolicy.Slot(3, 1), slots[2])
        assertEquals(10, slots.last().start + slots.last().span)
    }

    @Test
    fun `disabled mapping remains one slot per date`() {
        val slots = DailyLargeTodayOverlayPolicy.slots(listOf(false, true, false), enabled = false)

        assertFalse(slots.any { it.span != 1 })
        assertEquals(listOf(0, 1, 2), slots.map { it.start })
    }

    @Test
    fun `slot mapping preserves missing logical columns`() {
        val slots =
            DailyLargeTodayOverlayPolicy.slots(
                columnIndices = listOf(0, 3, 5),
                todayFlags = listOf(false, true, false),
                enabled = true,
            )

        assertEquals(listOf(0, 3, 6), slots.map { it.start })
        assertEquals(listOf(1, 2, 1), slots.map { it.span })
    }

    @Test
    fun `far navigation keeps ten columns when widening would remove Today`() {
        val today = LocalDate.of(2026, 8, 4)
        val candidateRange =
            NavigationUtils.getVisibleDateRange(
                today = today,
                dateOffset = -8,
                numColumns = 9,
                skipYesterday = false,
            )
        val todayVisibleAfterWidening = today in candidateRange.first..candidateRange.second

        val decision = DailyLargeTodayOverlayPolicy.resolve(10, 5, true, todayVisibleAfterWidening)

        assertFalse(decision.enabled)
        assertEquals(10, decision.displayColumns)
    }
}
