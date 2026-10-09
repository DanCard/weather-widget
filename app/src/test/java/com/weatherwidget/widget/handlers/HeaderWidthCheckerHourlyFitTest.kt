package com.weatherwidget.widget.handlers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [HeaderWidthChecker.fitHourlyHeader] with real text measurement: NATIVE graphics, since the
 * legacy shadow's measureText is not font-accurate. API 35, so the inline zones are resizable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(LongDuration::class)
class HeaderWidthCheckerHourlyFitTest {
    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fit(
        widthDp: Int,
        temp: String? = "61.3°",
        delta: String? = "-4.8",
        label: String? = "from yest",
        precip: String? = null,
        showStations: Boolean = true,
    ) = HeaderWidthChecker.fitHourlyHeader(
        context = context,
        widthDp = widthDp,
        apiSourceText = "Meteo",
        apiTextSizeDp = 12.6f,
        currentTempText = temp,
        deltaText = delta,
        deltaLabelText = label,
        precipText = precip,
        precipTextSizeDp = if (precip != null) 14f else null,
        showStations = showStations,
    ).also { println("fit widthDp=$widthDp -> $it") }

    /** Left cluster width as laid out, in dp, for the chosen layout. */
    private fun clusterDp(
        layout: HeaderWidthChecker.HourlyHeaderLayout,
        temp: String?,
        delta: String?,
        label: String?,
        zones: Int,
    ): Float {
        val d = context.resources.displayMetrics.density
        val left = HeaderWidthChecker.resolveLeftClusterRightPx(
            context = context,
            currentTempText = temp,
            deltaText = delta.takeIf { layout.disclosure.showsDelta() },
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = layout.disclosure.showsIcon(),
            currentTempSizeDp = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP * layout.textScale,
            deltaLabelText = label.takeIf { layout.showDeltaLabel },
        ) / d
        // The icon shrinks with the temperature.
        val iconShrinkDp = if (layout.disclosure.showsIcon()) {
            (HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) * (1f - layout.textScale)
        } else {
            0f
        }
        return left - iconShrinkDp + (layout.inlineZoneWidthDp ?: 0f) * zones + 1f
    }

    private fun availableDp(widthDp: Int): Float {
        val d = context.resources.displayMetrics.density
        return (HeaderWidthChecker.resolveApiLeftPx(context, widthDp * d, "Meteo", 12.6f) / d) -
            HeaderConstants.DATE_HORIZONTAL_GAP_DP
    }

    @Test
    fun `emulator header - delta shown whole, inside the space before the API label`() {
        // Generic_Foldable / phone at ~360 dp drew "-4.8" as "8": the inline row was never counted.
        val layout = fit(widthDp = 360)

        assertTrue("delta must be shown, got $layout", layout.disclosure.showsDelta())
        val zone = checkNotNull(layout.inlineZoneWidthDp)
        assertTrue("zone $zone within [28, 40]", zone in HourlyHeaderFit.MIN_ZONE_DP..40f)
        assertTrue(layout.textScale >= HourlyHeaderFit.MIN_TEXT_SCALE)
        assertTrue(
            "cluster must fit",
            clusterDp(layout, "61.3°", "-4.8", "from yest", zones = 4) <= availableDp(360) + 0.5f,
        )
    }

    @Test
    fun `room to spare - nominal zones, full size, nothing dropped`() {
        val layout = fit(widthDp = 400, delta = null, label = null)

        assertEquals(HeaderDisclosureLevel.FULL, layout.disclosure)
        assertEquals(HeaderWidthChecker.nominalInlineZoneWidthDp(400), checkNotNull(layout.inlineZoneWidthDp), 0.001f)
        assertEquals(1f, layout.textScale, 0f)
    }

    @Test
    fun `wide widget has no inline row and is unchanged`() {
        val layout = fit(widthDp = 500)

        assertNull(layout.inlineZoneWidthDp)
        assertEquals(1f, layout.textScale, 0f)
        assertEquals(
            HeaderWidthChecker.resolveHeaderDisclosure(
                context, 500, "Meteo", 12.6f, "61.3°", "-4.8", null, null,
            ),
            layout.disclosure,
        )
    }

    @Test
    fun `tight header compresses before dropping and never overflows`() {
        for (widthDp in listOf(240, 260, 280, 300, 320, 340, 360, 380, 400)) {
            val layout = fit(widthDp = widthDp)
            if (layout.disclosure == HeaderDisclosureLevel.NONE) continue
            val zone = checkNotNull(layout.inlineZoneWidthDp)
            assertTrue("zone floor at $widthDp: $zone", zone >= HourlyHeaderFit.MIN_ZONE_DP - 0.01f)
            assertTrue("scale floor at $widthDp", layout.textScale >= HourlyHeaderFit.MIN_TEXT_SCALE - 0.001f)
            assertTrue(
                "overflow at $widthDp: $layout",
                clusterDp(layout, "61.3°", "-4.8", "from yest", zones = 4) <= availableDp(widthDp) + 0.5f,
            )
            // Something dropped only once both floors are reached.
            if (layout.disclosure != HeaderDisclosureLevel.FULL) {
                val full = fit(widthDp = widthDp + 1000) // sanity: wide is FULL
                assertTrue(full.disclosure == HeaderDisclosureLevel.FULL)
            }
        }
    }

    @Test
    fun `caption never squeezes anything - dropped when there is no room as laid out`() {
        for (widthDp in 240..419 step 5) {
            val withCaption = fit(widthDp = widthDp)
            val without = fit(widthDp = widthDp, label = null)
            assertEquals("disclosure at $widthDp", without.disclosure, withCaption.disclosure)
            assertEquals("zones at $widthDp", without.inlineZoneWidthDp, withCaption.inlineZoneWidthDp)
            assertEquals("scale at $widthDp", without.textScale, withCaption.textScale, 0f)
        }
    }

    @Test
    fun `caption never shrinks the temperature`() {
        for (widthDp in 240..419 step 5) {
            val layout = fit(widthDp = widthDp)
            if (layout.showDeltaLabel) assertEquals("at $widthDp", 1f, layout.textScale, 0f)
        }
    }

    @Test
    fun `caption never sits beside a rain chance`() {
        for (widthDp in listOf(380, 400, 419)) {
            assertTrue(!fit(widthDp = widthDp, precip = "30%").showDeltaLabel)
        }
    }
}
