package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure-function tests for the width->columns (and height->rows) mapping.
 *
 * These pin the *intentional* round-up bias that lets the daily view fit an extra forecast
 * day on widgets near a column boundary: the Pixel 7 Pro (~373dp) shows 6 days, and the
 * Samsung Fold 4 full-width widget (~574dp) shows 9. See COLUMN_FIT_BIAS_DP in
 * WidgetSizeCalculator.
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
    fun columns_foldFullWidthFitsNineDays() {
        // Samsung Fold 4 full-width (6-span) widget reports ~574dp -> 9 columns
        // (yesterday + today + 7 forecast = window -1..+7, within ForecastHorizon.BASELINE_DAYS).
        // Verified against live device options on RFCT71FR9NT.
        assertEquals(9, WidgetSizeCalculator.columnsForWidthDp(574))
    }

    @Test
    fun columns_roundUpBias_atEightToNineBoundary() {
        // The 8->9 boundary (the one that gives the Fold its 9th day) sits at width >= 565dp.
        // Pinning both sides keeps the extra column a deliberate, stable choice.
        assertEquals(8, WidgetSizeCalculator.columnsForWidthDp(564))
        assertEquals(9, WidgetSizeCalculator.columnsForWidthDp(566))
    }

    @Test
    fun columns_roundUpBias_atFiveToSixBoundary() {
        // With the current bias the 5->6 boundary sits at width >= 355dp.
        assertEquals(5, WidgetSizeCalculator.columnsForWidthDp(354))
        assertEquals(6, WidgetSizeCalculator.columnsForWidthDp(355))
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
