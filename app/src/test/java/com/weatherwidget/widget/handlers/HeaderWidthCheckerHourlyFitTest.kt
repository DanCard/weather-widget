package com.weatherwidget.widget.handlers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hourly header through [HeaderWidthChecker.measureHeader] and the shared [HeaderFit], with real
 * text measurement: NATIVE graphics, since the legacy shadow's measureText is not font-accurate.
 * API 35, so the inline zones are resizable and the buttons can sit off the middle.
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

    private fun measure(
        widthDp: Int,
        temp: String? = "61.3°",
        delta: String? = "-4.8",
        label: String? = "from yest",
        precip: String? = null,
    ) = HeaderWidthChecker.measureHeader(
        context = context,
        widthDp = widthDp,
        headerScale = 1f,
        apiSourceText = "Meteo",
        apiTextSizeDp = 12.6f,
        currentTempText = temp,
        currentTempSizeDp = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
        deltaText = delta,
        deltaLabelText = label,
        precipText = precip,
        precipTextSizeDp = if (precip != null) 14f else null,
        dateText = null,
        includeIcon = true,
        baseButtons = 4,
        wantHome = false,
        wantMonths = false,
        hourly = true,
    )

    private fun fit(widthDp: Int, label: String? = "from yest", precip: String? = null) =
        HeaderFit.resolve(measure(widthDp, label = label, precip = precip)).also { println("fit widthDp=$widthDp -> $it") }

    private val density get() = context.resources.displayMetrics.density

    @Test
    fun `emulator header - delta shown whole, with all four buttons`() {
        // Generic_Foldable / phone at ~360 dp drew "-4.8" as "8": the inline row was never counted.
        val plan = fit(widthDp = 360)
        assertTrue("delta must be shown, got $plan", plan.showDelta)
        assertEquals(4, plan.buttons)
        if (plan.placement == HeaderFit.ButtonPlacement.INLINE) {
            assertTrue("zone ${plan.inlineZone / density}", plan.inlineZone / density >= HeaderWidthChecker.HOURLY_MIN_INLINE_ZONE_DP - 0.01f)
        }
        assertTrue(plan.textScale >= HeaderWidthChecker.HOURLY_MIN_TEXT_SCALE)
    }

    @Test
    fun `wide widget - buttons on the shared line, full size`() {
        val m = measure(widthDp = 500)
        val plan = HeaderFit.resolve(m)
        assertEquals(HeaderFit.ButtonPlacement.CENTER to HeaderFit.Seam.RIGHT_OF_MIDDLE, plan.placement to plan.seam)
        assertEquals(1f, plan.textScale, 0f)
        val (_, right) = HeaderFit.buttonsSpan(m, plan)!!
        assertEquals((250f + HeaderFit.SEAM_OFFSET_DP) * density, right, 1f)
    }

    @Test
    fun `tight header squeezes before dropping, within the floors`() {
        for (widthDp in listOf(240, 260, 280, 300, 320, 340, 360, 380, 400)) {
            val plan = fit(widthDp = widthDp)
            assertTrue("scale floor at $widthDp: $plan", plan.textScale >= HeaderWidthChecker.HOURLY_MIN_TEXT_SCALE - 0.001f)
            if (plan.placement == HeaderFit.ButtonPlacement.INLINE) {
                assertTrue("zone floor at $widthDp: $plan", plan.inlineZone / density >= HeaderWidthChecker.HOURLY_MIN_INLINE_ZONE_DP - 0.01f)
                assertTrue("zone ceiling at $widthDp: $plan", plan.inlineZone / density <= HeaderWidthChecker.nominalInlineZoneWidthDp(widthDp) + 0.01f)
            } else {
                assertEquals("text shrinks only beside inline buttons ($widthDp)", 1f, plan.textScale, 0f)
            }
        }
    }

    @Test
    fun `caption never squeezes anything - dropped when there is no room as laid out`() {
        for (widthDp in 240..419 step 5) {
            val withCaption = fit(widthDp = widthDp)
            val without = fit(widthDp = widthDp, label = null)
            assertEquals("zones at $widthDp", without.inlineZone, withCaption.inlineZone)
            assertEquals("scale at $widthDp", without.textScale, withCaption.textScale, 0f)
            if (withCaption.showCaption) assertEquals("at $widthDp", 1f, withCaption.textScale, 0f)
        }
    }

    @Test
    fun `caption never sits beside a rain chance`() {
        for (widthDp in listOf(380, 400, 419, 500)) {
            assertFalse(fit(widthDp = widthDp, precip = "30%").showCaption)
        }
    }
}
