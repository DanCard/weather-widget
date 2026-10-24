package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Width->columns and height->rows, one rule for the widget and the desktop popup. These pin the
 * column density: the Fold 4 cover (~337dp) and Pixel 7 Pro (~373dp) get 7 slots, the Fold 4
 * full-width widget (~574dp) 10 and the Fold 4 inner widget (~662dp) 12
 * (plans/261017-daily-density-narrow-only.md).
 */
@Category(ShortDuration::class)
class DailyLayoutConstantsTest {
    @Test
    fun `layout constants have expected values for compact daily view`() {
        assertEquals(90, DailyLayoutConstants.CELL_HEIGHT_DP)
        assertEquals(130, DailyLayoutConstants.ICON_WIDTH_THRESHOLD_DP)
    }

    @Test
    fun `narrow layouts use 55dp cells, 436dp and wider use 60dp`() {
        assertEquals(55, DailyLayoutConstants.cellWidthDp(337))
        assertEquals(55, DailyLayoutConstants.cellWidthDp(435))
        assertEquals(60, DailyLayoutConstants.cellWidthDp(436))
        assertEquals(60, DailyLayoutConstants.cellWidthDp(662))
        assertTrue(DailyLayoutConstants.NARROW_CELL_WIDTH_DP < DailyLayoutConstants.WIDE_CELL_WIDTH_DP)
    }

    @Test
    fun columns_matchDocumentedSizeTable() {
        // Representative widths: 55dp cells below 436dp, 60dp from there; bias 30.
        assertEquals(1, DailyLayoutConstants.columnsForWidthDp(40))
        assertEquals(3, DailyLayoutConstants.columnsForWidthDp(130))
        assertEquals(4, DailyLayoutConstants.columnsForWidthDp(210))
        assertEquals(6, DailyLayoutConstants.columnsForWidthDp(280))
        assertEquals(7, DailyLayoutConstants.columnsForWidthDp(350))
    }

    @Test
    fun columns_pixel7ProFitsSevenDays() {
        // Pixel 7 Pro reports ~373dp for the daily widget -> 7 columns.
        // Verified on device 2A191FDH300PPW.
        assertEquals(7, DailyLayoutConstants.columnsForWidthDp(373))
    }

    @Test
    fun columns_foldCoverFitsSevenSlotsForSixDaysWithOverlay() {
        // Samsung Fold 4 cover display reports ~337dp -> 7 columns (slots).
        // With the detailed Today overlay active (span=2), this yields 6 rendered days (Today + 5).
        // Verified on live device RFCT70G352Y.
        assertEquals(7, DailyLayoutConstants.columnsForWidthDp(337))
    }

    @Test
    fun columns_foldFullWidthFitsTenColumns() {
        // Samsung Fold 4 full-width (6-span) widget reports ~574dp -> 10 columns (60dp cells).
        assertEquals(10, DailyLayoutConstants.columnsForWidthDp(574))
    }

    @Test
    fun columns_foldInnerWidgetFitsTwelveSlotsForElevenDays() {
        // Fold 4 inner widget 345 reports 662dp. 55dp cells made it 13 slots (12 days), too dense;
        // 60dp cells give 12 slots = 11 days with the Today overlay. Verified on RFCT70G352Y.
        assertEquals(12, DailyLayoutConstants.columnsForWidthDp(662))
        // 11->12 boundary under the wide cell.
        assertEquals(11, DailyLayoutConstants.columnsForWidthDp(659))
        assertEquals(12, DailyLayoutConstants.columnsForWidthDp(660))
    }

    @Test
    fun columns_switchToWideCellsKeepsTheCount() {
        // Both cell widths give 8 columns either side of the 436dp switch.
        assertEquals(8, DailyLayoutConstants.columnsForWidthDp(435))
        assertEquals(8, DailyLayoutConstants.columnsForWidthDp(436))
    }

    @Test
    fun columns_neverDecreaseAsWidthGrows() {
        // A cell-width switch at the wrong width would give a wider widget fewer columns.
        var previous = DailyLayoutConstants.columnsForWidthDp(0)
        for (widthDp in 1..1000) {
            val columns = DailyLayoutConstants.columnsForWidthDp(widthDp)
            assertTrue("${widthDp}dp -> $columns columns, ${widthDp - 1}dp -> $previous", columns >= previous)
            previous = columns
        }
    }

    @Test
    fun columns_roundUpBias_atSixToSevenBoundary() {
        // The 6->7 boundary sits at width >= 328dp.
        // Pinning both sides keeps the density a deliberate, stable choice.
        assertEquals(6, DailyLayoutConstants.columnsForWidthDp(327))
        assertEquals(7, DailyLayoutConstants.columnsForWidthDp(328))
    }

    @Test
    fun columns_roundUpBias_atFiveToSixBoundary() {
        // With the current density the 5->6 boundary sits at width >= 273dp.
        assertEquals(5, DailyLayoutConstants.columnsForWidthDp(272))
        assertEquals(6, DailyLayoutConstants.columnsForWidthDp(273))
    }

    @Test
    fun columns_neverBelowOne() {
        assertEquals(1, DailyLayoutConstants.columnsForWidthDp(0))
    }

    @Test
    fun rows_roundUpBias_atOneToTwoBoundary() {
        // Height analogue: the 1->2 row boundary sits at height >= 110dp ((110+25)/90 = 1.5).
        assertEquals(1, DailyLayoutConstants.rowsForHeightDp(109))
        assertEquals(2, DailyLayoutConstants.rowsForHeightDp(110))
        assertEquals(1, DailyLayoutConstants.rowsForHeightDp(0))
    }

    @Test
    fun `a cap limits the columns and never goes below one`() {
        assertEquals(9, DailyLayoutConstants.columnsForWidthDp(600, maxColumns = 9))
        assertEquals(11, DailyLayoutConstants.columnsForWidthDp(600)) // (600+30)/60 = 10.5
        assertEquals(1, DailyLayoutConstants.columnsForWidthDp(600, maxColumns = 0))
    }
}
