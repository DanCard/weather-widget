package com.weatherwidget.widget.handlers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.shared.util.HeaderLine
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared header line ([HeaderLine], `plans/261026-shared-header-line-reserve.md`) through
 * [HeaderWidthChecker.measureHeader] with real text measurement: a source tap from NWS (5 %) to
 * Meteo (70 %) moves neither the buttons nor the date. API 35, so the row can sit off the middle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(LongDuration::class)
class HeaderWidthCheckerLineTest {
    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private val line = HeaderLine.Inputs(
        sourceLabels = listOf("NWS", "Meteo"),
        rainLabels = listOf(HeaderContent.PrecipLabel("5%", 0.6f), HeaderContent.PrecipLabel("70%", 1f)),
    )

    private fun measure(widthDp: Int, source: String, rain: String, rainScale: Float, line: HeaderLine.Inputs?) =
        HeaderWidthChecker.measureHeader(
            context = context,
            widthDp = widthDp,
            headerScale = 1f,
            apiSourceText = source,
            apiTextSizeDp = 12.6f,
            currentTempText = "61.3°",
            currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = "-4.8",
            deltaLabelText = "from yest",
            precipText = rain,
            precipTextSizeDp = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * rainScale,
            dateText = "Mon 26",
            includeIcon = true,
            baseButtons = 2,
            wantHome = true,
            wantMonths = false,
            line = line,
            nominalDate = "Mon 26",
        )

    @Test
    fun aSourceTapMovesNeitherTheButtonsNorTheDate() {
        // 460dp: on the fixed 70dp line the wider rain % squeezed the zones, so every button moved.
        fun spans(line: HeaderLine.Inputs?) = listOf("NWS" to ("5%" to 0.6f), "Meteo" to ("70%" to 1f)).map { (source, rain) ->
            val m = measure(460, source, rain.first, rain.second, line)
            val plan = HeaderFit.resolve(m)
            HeaderFit.buttonsSpan(m, plan) to HeaderFit.dateLeft(m, plan)
        }
        val (nws, meteo) = spans(line)
        assertEquals("buttons and date stay put", nws, meteo)
        val (oldNws, oldMeteo) = spans(null)
        assertNotEquals("the fixed line moved them", oldNws, oldMeteo)
    }

    private fun scale(widthDp: Int, source: String, rain: String?, line: HeaderLine.Inputs?) =
        HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = widthDp,
            apiSourceText = source,
            apiTextSizeDp = 12.6f,
            currentTempText = "61.3°",
            deltaText = null,
            precipText = rain,
            precipTextSizeDp = rain?.let { HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP },
            line = line,
        )

    @Test
    fun aSourceTapDoesNotRescaleTheHeader() {
        // Fold 4, 2026-10-27: Google, the one source with a rain %, drew at ×1.0 and the dry
        // sources at ×1.35 — every tap onto or off Google rescaled the row and moved the buttons.
        // A wide right cluster, as on the Fold (174dp): only the rain % differs between sources.
        val label = "Tomorrow.io - Silurian"
        val wet = HeaderLine.Inputs(
            sourceLabels = listOf(label),
            rainLabels = listOf(HeaderContent.PrecipLabel("70%", 1f)),
        )
        val flipped = (450..1000 step 5).firstOrNull { scale(it, label, null, null) != scale(it, label, "70%", null) }
        assertNotNull("some width rescaled on live text alone", flipped)
        assertEquals(
            "same scale on every source at ${flipped}dp",
            scale(flipped!!, label, null, wet),
            scale(flipped, label, "70%", wet),
        )
    }

    @Test
    fun buttonScaleIsWidthOnly() {
        val density = context.resources.displayMetrics.density
        val centre = HeaderFit.Plan(buttons = 3, placement = HeaderFit.ButtonPlacement.CENTER)
        val inline = HeaderFit.Plan(buttons = 3, placement = HeaderFit.ButtonPlacement.INLINE, inlineZone = 48f * density)
        assertEquals(1.35f, HeaderWidthChecker.buttonScale(545, centre, density), 0.001f)
        assertEquals(1.35f, HeaderWidthChecker.buttonScale(545, inline, density), 0.001f)
        assertEquals(1f, HeaderWidthChecker.buttonScale(400, centre, density), 0.001f)
        // A zone squeezed to 28dp keeps 6dp of air around the icon: 22 / 20.
        val squeezed = inline.copy(inlineZone = 28f * density)
        assertEquals(1.1f, HeaderWidthChecker.buttonScale(545, squeezed, density), 0.001f)
    }
}
