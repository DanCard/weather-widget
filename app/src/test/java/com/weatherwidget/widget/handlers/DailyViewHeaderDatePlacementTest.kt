package com.weatherwidget.widget.handlers

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewHeaderDatePlacementTest {

    @Test
    fun `resolveHeaderDatePlacement hides date when fewer than six columns are shown`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyHeaderBinder.resolveHeaderDatePlacement(
                context = context,
                widthDp = 300,
                numColumns = 5,
                currentTempText = "1234567890°",
                deltaText = null,
                precipText = null,
                precipTextSizeDp = null,
                apiSourceText = "NWS",
                apiTextSizeDp = 16f,
                dateText = "Sun 19",
            )

        assertNull(placement)
    }

    @Test
    fun `resolveHeaderDatePlacement prefers center when header text leaves room`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyHeaderBinder.resolveHeaderDatePlacement(
                context = context,
                widthDp = 360,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = null,
                precipText = null,
                precipTextSizeDp = null,
                apiSourceText = "NWS",
                apiTextSizeDp = 16f,
                dateText = "Sun 19",
            )

        assertEquals(DailyHeaderBinder.HeaderDatePlacement.CENTER, placement)
    }

    @Test
    fun `resolveHeaderDatePlacement keeps center when header text still leaves room`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyHeaderBinder.resolveHeaderDatePlacement(
                context = context,
                widthDp = 220,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = "+2.6",
                precipText = "54%",
                precipTextSizeDp = 26f,
                apiSourceText = "NWS",
                apiTextSizeDp = 16f,
                dateText = "Sun 19",
            )

        assertEquals(DailyHeaderBinder.HeaderDatePlacement.CENTER, placement)
    }

    @Test
    fun `resolveHeaderDatePlacementFromBounds moves date right when center collides`() {
        val placement = DailyHeaderBinder.resolveHeaderDatePlacementFromBounds(
            numColumns = 8,
            widthPx = 360f,
            leftClusterRight = 175f,
            apiLeft = 330f,
            dateWidth = 60f,
            gapPx = 6f,
            rightMarginPx = 112f,
        )

        assertEquals(DailyHeaderBinder.HeaderDatePlacement.RIGHT, placement)
    }

    @Test
    fun `resolveHeaderPrecipPlacement keeps precip in header when date still fits`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyHeaderBinder.resolveHeaderPrecipPlacement(
                context = context,
                widthDp = 360,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = "+2.6",
                precipText = "54%",
                precipTextSizeDp = 26f,
                apiSourceText = "NWS",
                apiTextSizeDp = 16f,
                dateText = "Sun 19",
                headerCanShowPrecip = true,
                includeIcon = false,
            )

        assertEquals(
            DailyHeaderBinder.HeaderPrecipPlacement(showHeaderPrecip = true, allowTodayColumnPrecip = false),
            placement,
        )
    }

    @Test
    fun `resolveHeaderPrecipPlacement does not move precip to today column when header cannot show precip but date fits`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyHeaderBinder.resolveHeaderPrecipPlacement(
                context = context,
                widthDp = 220,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = null,
                precipText = "100%",
                precipTextSizeDp = 26f,
                apiSourceText = "NWS",
                apiTextSizeDp = 16f,
                dateText = "Sun 19",
                headerCanShowPrecip = false,
                includeIcon = false,
            )

        assertEquals(
            DailyHeaderBinder.HeaderPrecipPlacement(showHeaderPrecip = false, allowTodayColumnPrecip = false),
            placement,
        )
    }

    @Test
    fun `resolveHeaderPrecipPlacement keeps header precip when right date placement fits`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyHeaderBinder.resolveHeaderPrecipPlacement(
                context = context,
                widthDp = 230,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = "+2.6",
                precipText = "100%",
                precipTextSizeDp = 26f,
                apiSourceText = "NWS",
                apiTextSizeDp = 16f,
                dateText = "Sun 19",
                headerCanShowPrecip = true,
                includeIcon = false,
            )

        assertEquals(
            DailyHeaderBinder.HeaderPrecipPlacement(showHeaderPrecip = true, allowTodayColumnPrecip = false),
            placement,
        )
    }


    @Test
    fun `resolveHeaderPrecipPlacement does not move precip when date still cannot fit`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyHeaderBinder.resolveHeaderPrecipPlacement(
                context = context,
                widthDp = 110,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = null,
                precipText = "100%",
                precipTextSizeDp = 26f,
                apiSourceText = "NWS",
                apiTextSizeDp = 16f,
                dateText = "Sun 19",
                headerCanShowPrecip = true,
                includeIcon = false,
            )

        assertEquals(
            DailyHeaderBinder.HeaderPrecipPlacement(showHeaderPrecip = true, allowTodayColumnPrecip = false),
            placement,
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `current temp text size ignores font scale`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val metrics = context.resources.displayMetrics
        val originalScaledDensity = metrics.scaledDensity
        val expectedPx = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP * metrics.density

        try {
            metrics.scaledDensity = metrics.density * 0.8f
            val samsungScaleSize = DailyHeaderBinder.currentTempTextSizePx(context)

            metrics.scaledDensity = metrics.density * 1.15f
            val pixelScaleSize = DailyHeaderBinder.currentTempTextSizePx(context)

            assertEquals(expectedPx, samsungScaleSize, 0.01f)
            assertEquals(expectedPx, pixelScaleSize, 0.01f)
        } finally {
            metrics.scaledDensity = originalScaledDensity
        }
    }

    @Test
    fun `the home button takes its space from the date, not from the buttons`() {
        // Pure bounds (no font engine): a span that seats the date beside a two-icon slot must give
        // it up once the home button widens that slot to three. This is the whole of the "higher
        // priority than the day-of-week" rule — the date reads the reserved slot and yields; it is
        // never asked whether the button may appear.
        fun placement(centerIconsWidth: Float) =
            DailyHeaderBinder.resolveHeaderDatePlacementFromBounds(
                numColumns = 7,
                widthPx = 440f,
                leftClusterRight = 150f,
                apiLeft = 330f,
                dateWidth = 50f,
                gapPx = 6f,
                rightMarginPx = 40f,
                centerIconsWidth = centerIconsWidth,
            )

        // Two 40px zones: the slot ends at 260, leaving the free span 266..324 — 58px, enough for
        // the 50px date. Three zones push the slot's right edge to 280 and the span down to 38px,
        // so the date has nowhere left to go.
        assertEquals(DailyHeaderBinder.HeaderDatePlacement.RIGHT, placement(80f))
        assertNull(placement(120f))
    }
}
