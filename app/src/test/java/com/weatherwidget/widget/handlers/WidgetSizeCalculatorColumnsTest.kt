package com.weatherwidget.widget.handlers

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import com.weatherwidget.shared.graph.DailyLayoutConstants
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WidgetQueryWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Widget-side sizing: the forecast horizon against the widest widgets' columns, and the host
 * orientation rules. The width->columns / height->rows math itself is `:shared`
 * `DailyLayoutConstantsTest`.
 *
 * Kept as plain JUnit (no Robolectric) since the math is decoupled from Android plumbing.
 */
@Category(ShortDuration::class)
class WidgetSizeCalculatorColumnsTest {
    @Test
    fun `daily forecast horizon covers the render horizon of the widest widgets`() {
        // The daily view renders today + numColumns - 2 at offset 0, so the forecast/gap-fill
        // horizon must be at least that far out on EVERY path. When startup and the worker used 7
        // while the Fold's 10-column widget rendered today+8, that column got neither a real row
        // nor a GENERIC_GAP row and painted a grey "cloudy" day over correct climate normals.
        val widestRealisticWidgetDp = 1280 // ~10" tablet, full width
        for (widthDp in intArrayOf(373, 574, 800, widestRealisticWidgetDp)) {
            val renderHorizonDays = DailyLayoutConstants.columnsForWidthDp(widthDp) - 2
            assertTrue(
                "DAILY_FORECAST_DAYS=${WidgetQueryWindows.DAILY_FORECAST_DAYS} must cover the " +
                    "today+$renderHorizonDays render horizon of a ${widthDp}dp widget",
                WidgetQueryWindows.DAILY_FORECAST_DAYS >= renderHorizonDays,
            )
        }
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
