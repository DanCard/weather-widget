package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure-function tests for the width->columns (and height->rows) mapping.
 *
 * These pin the *intentional* round-up bias that lets the daily view fit an extra forecast
 * day on widgets that are within ~half a cell of the next column (e.g. the Pixel 7 Pro, which
 * reports ~373dp and therefore shows 6 days). See COLUMN_FIT_BIAS_DP in WidgetSizeCalculator.
 *
 * Kept as plain JUnit (no Robolectric) since the math is decoupled from Android plumbing.
 */
@Category(ShortDuration::class)
class WidgetSizeCalculatorColumnsTest {
    @Test
    fun columns_matchDocumentedSizeTable() {
        // Mirrors the size examples in CLAUDE.md / WidgetSizeCalculator comments.
        assertEquals(1, WidgetSizeCalculator.columnsForWidthDp(40))
        assertEquals(2, WidgetSizeCalculator.columnsForWidthDp(130))
        assertEquals(3, WidgetSizeCalculator.columnsForWidthDp(210))
        assertEquals(4, WidgetSizeCalculator.columnsForWidthDp(280))
        assertEquals(5, WidgetSizeCalculator.columnsForWidthDp(350))
    }

    @Test
    fun columns_pixel7ProFitsSixDays() {
        // Pixel 7 Pro reports ~373dp for the daily widget -> 6 columns (yesterday + today + 4).
        // Verified on device 2A191FDH300PPW: renders Fri, Today, Sun, Mon, Tue, Wed.
        assertEquals(6, WidgetSizeCalculator.columnsForWidthDp(373))
    }

    @Test
    fun columns_roundUpBias_atFiveToSixBoundary() {
        // The bias rounds UP at half a cell: the 5->6 boundary sits at width >= 370dp.
        // Pinning both sides guarantees the extra day is a deliberate, stable choice.
        assertEquals(5, WidgetSizeCalculator.columnsForWidthDp(369))
        assertEquals(6, WidgetSizeCalculator.columnsForWidthDp(370))
    }

    @Test
    fun columns_neverBelowOne() {
        assertEquals(1, WidgetSizeCalculator.columnsForWidthDp(0))
    }

    @Test
    fun rows_roundUpBias_atOneToTwoBoundary() {
        // Height analogue: the 1->2 row boundary sits at height >= 110dp ((110+25)/90 = 1.5).
        assertEquals(1, WidgetSizeCalculator.rowsForHeightDp(109))
        assertEquals(2, WidgetSizeCalculator.rowsForHeightDp(110))
        assertEquals(1, WidgetSizeCalculator.rowsForHeightDp(0))
    }
}
