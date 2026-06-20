package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure-function tests for the width->columns (and height->rows) mapping.
 *
 * These pin the column density: the Pixel 7 Pro (~373dp) shows 7 days and the Samsung Fold 4
 * full-width widget (~574dp) shows 10. Density is set by CELL_WIDTH_DP plus the COLUMN_FIT_BIAS_DP
 * round-up; see WidgetSizeCalculator.
 *
 * Kept as plain JUnit (no Robolectric) since the math is decoupled from Android plumbing.
 */
@Category(ShortDuration::class)
class WidgetSizeCalculatorColumnsTest {
    @Test
    fun columns_matchDocumentedSizeTable() {
        // Representative widths under the current density (CELL_WIDTH_DP=60, bias=30).
        assertEquals(1, WidgetSizeCalculator.columnsForWidthDp(40))
        assertEquals(3, WidgetSizeCalculator.columnsForWidthDp(130))
        assertEquals(4, WidgetSizeCalculator.columnsForWidthDp(210))
        assertEquals(5, WidgetSizeCalculator.columnsForWidthDp(280))
        assertEquals(6, WidgetSizeCalculator.columnsForWidthDp(350))
    }

    @Test
    fun columns_pixel7ProFitsSevenDays() {
        // Pixel 7 Pro reports ~373dp for the daily widget -> 7 columns.
        // Verified on device 2A191FDH300PPW.
        assertEquals(7, WidgetSizeCalculator.columnsForWidthDp(373))
    }

    @Test
    fun columns_foldFullWidthFitsTenDays() {
        // Samsung Fold 4 full-width (6-span) widget reports ~574dp -> 10 columns
        // (window -1..+8; +8 and NWS's +7 use the climate-normal / extension fallback).
        // Verified against live device options on RFCT71FR9NT.
        assertEquals(10, WidgetSizeCalculator.columnsForWidthDp(574))
    }

    @Test
    fun columns_roundUpBias_atSixToSevenBoundary() {
        // The 6->7 boundary (the one that gives the Pixel 7 Pro its 7th day) sits at width >= 360dp.
        // Pinning both sides keeps the density a deliberate, stable choice.
        assertEquals(6, WidgetSizeCalculator.columnsForWidthDp(359))
        assertEquals(7, WidgetSizeCalculator.columnsForWidthDp(360))
    }

    @Test
    fun columns_roundUpBias_atFiveToSixBoundary() {
        // With the current density the 5->6 boundary sits at width >= 300dp.
        assertEquals(5, WidgetSizeCalculator.columnsForWidthDp(299))
        assertEquals(6, WidgetSizeCalculator.columnsForWidthDp(300))
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
