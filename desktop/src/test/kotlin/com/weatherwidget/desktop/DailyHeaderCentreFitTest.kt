package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The desktop daily header's date-vs-buttons rule. Pure arithmetic, so it is asserted without a
 * window — the Compose UI test only has to prove the composable asks this function.
 */
@Category(ShortDuration::class)
class DailyHeaderCentreFitTest {

    private val icon = 15f
    private val spacing = 10f
    private val date = 34f

    @Test
    fun `icons width counts the gaps between them, not after them`() {
        assertEquals(15f, DailyHeaderCentreFit.iconsWidthDp(1, icon, spacing), 0.01f)
        assertEquals(40f, DailyHeaderCentreFit.iconsWidthDp(2, icon, spacing), 0.01f)
        assertEquals(65f, DailyHeaderCentreFit.iconsWidthDp(3, icon, spacing), 0.01f)
        assertEquals(0f, DailyHeaderCentreFit.iconsWidthDp(0, icon, spacing), 0.01f)
    }

    @Test
    fun `a roomy centre cluster keeps the date`() {
        assertTrue(DailyHeaderCentreFit.showDate(200f, 2, icon, spacing, date))
    }

    @Test
    fun `the home button pushes the date out before it pushes a button out`() {
        // 2 icons + gap + date == 84; 3 icons + gap + date == 109. At 100dp of leftover the date is
        // what goes — the buttons are not consulted, which is the whole priority rule.
        assertTrue(DailyHeaderCentreFit.showDate(100f, 2, icon, spacing, date))
        assertFalse(DailyHeaderCentreFit.showDate(100f, 3, icon, spacing, date))
    }

    @Test
    fun `an exactly-fitting date still shows`() {
        assertTrue(DailyHeaderCentreFit.showDate(109f, 3, icon, spacing, date))
        assertFalse(DailyHeaderCentreFit.showDate(108.9f, 3, icon, spacing, date))
    }

    @Test
    fun `a starved cluster drops the date rather than reporting a negative fit`() {
        // weight(1f) can hand back nothing at all when the content-sized clusters fill the row.
        assertFalse(DailyHeaderCentreFit.showDate(0f, 3, icon, spacing, date))
    }

    @Test
    fun `an unmeasured date is not drawn`() {
        assertFalse(DailyHeaderCentreFit.showDate(500f, 3, icon, spacing, dateWidthDp = 0f))
    }
}
