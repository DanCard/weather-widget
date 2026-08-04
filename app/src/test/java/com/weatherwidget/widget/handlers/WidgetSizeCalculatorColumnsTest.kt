package com.weatherwidget.widget.handlers

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WidgetQueryWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `daily forecast horizon covers the render horizon of the widest widgets`() {
        // The daily view renders today + numColumns - 2 at offset 0, so the forecast/gap-fill
        // horizon must be at least that far out on EVERY path. When startup and the worker used 7
        // while the Fold's 10-column widget rendered today+8, that column got neither a real row
        // nor a GENERIC_GAP row and painted a grey "cloudy" day over correct climate normals.
        val widestRealisticWidgetDp = 1280 // ~10" tablet, full width
        for (widthDp in intArrayOf(373, 574, 800, widestRealisticWidgetDp)) {
            val renderHorizonDays = WidgetSizeCalculator.columnsForWidthDp(widthDp) - 2
            assertTrue(
                "DAILY_FORECAST_DAYS=${WidgetQueryWindows.DAILY_FORECAST_DAYS} must cover the " +
                    "today+$renderHorizonDays render horizon of a ${widthDp}dp widget",
                WidgetQueryWindows.DAILY_FORECAST_DAYS >= renderHorizonDays,
            )
        }
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

    @Test
    fun `nosensor home uses natural portrait while a foreground app is landscape`() {
        val decision =
            WidgetSizeCalculator.resolveHostOrientation(
                deviceOrientation = Configuration.ORIENTATION_LANDSCAPE,
                naturalOrientation = Configuration.ORIENTATION_PORTRAIT,
                homeScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR,
            )

        assertEquals(Configuration.ORIENTATION_PORTRAIT, decision.orientation)
        assertEquals("home_nosensor_natural", decision.source)
    }

    @Test
    fun `Pixel Launcher uses natural orientation when its manifest policy is unspecified`() {
        val decision =
            WidgetSizeCalculator.resolveHostOrientation(
                deviceOrientation = Configuration.ORIENTATION_LANDSCAPE,
                naturalOrientation = Configuration.ORIENTATION_PORTRAIT,
                homeScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                homePackageName = "com.google.android.apps.nexuslauncher",
            )

        assertEquals(Configuration.ORIENTATION_PORTRAIT, decision.orientation)
        assertEquals("pixel_launcher_natural", decision.source)
    }

    @Test
    fun `rotating home follows the current device orientation`() {
        val decision =
            WidgetSizeCalculator.resolveHostOrientation(
                deviceOrientation = Configuration.ORIENTATION_LANDSCAPE,
                naturalOrientation = Configuration.ORIENTATION_PORTRAIT,
                homeScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            )

        assertEquals(Configuration.ORIENTATION_LANDSCAPE, decision.orientation)
        assertEquals("device_configuration", decision.source)
    }

    @Test
    fun `explicit home orientation overrides device orientation`() {
        val portrait =
            WidgetSizeCalculator.resolveHostOrientation(
                deviceOrientation = Configuration.ORIENTATION_LANDSCAPE,
                naturalOrientation = Configuration.ORIENTATION_PORTRAIT,
                homeScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            )
        val landscape =
            WidgetSizeCalculator.resolveHostOrientation(
                deviceOrientation = Configuration.ORIENTATION_PORTRAIT,
                naturalOrientation = Configuration.ORIENTATION_PORTRAIT,
                homeScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            )

        assertEquals(Configuration.ORIENTATION_PORTRAIT, portrait.orientation)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, landscape.orientation)
    }

    @Test
    fun `landscape at ninety degrees recovers portrait natural orientation`() {
        assertEquals(
            Configuration.ORIENTATION_PORTRAIT,
            WidgetSizeCalculator.naturalOrientationForRotation(
                currentOrientation = Configuration.ORIENTATION_LANDSCAPE,
                rotation = Surface.ROTATION_90,
            ),
        )
    }
}
