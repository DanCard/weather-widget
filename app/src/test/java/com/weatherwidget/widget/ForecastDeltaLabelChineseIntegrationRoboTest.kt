package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.shared.graph.ForecastDeltaLabel
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Integration test (zh-rCN): `TemperatureGraphRenderer` -> `TemperatureGraphAnnotationRenderer` ->
 * `ForecastDeltaLabel` for the hourly `-0.2 较预报` label, asserting the mixed-size split actually
 * reaches the canvas in a localized locale.
 *
 * The label is two runs on one baseline — the value at the staleness size and the localized caption
 * at `ForecastDeltaLabel.SUFFIX_FONT_SCALE` of it — which a rendered bitmap cannot reveal. The debug
 * hook reports both run sizes and the placed box, so this checks the split and that the reserved
 * empty-space box still matches the combined ink width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
@Category(Localization::class)
class ForecastDeltaLabelChineseIntegrationRoboTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val start: LocalDateTime = LocalDateTime.of(2026, 9, 9, 10, 0)

    /**
     * A low, gently varying curve leaves the upper band empty, so the center-first anchor places the
     * label without competition.
     */
    private fun hours(): List<HourData> =
        listOf(50f, 52f, 54f, 53f, 51f).mapIndexed { offset, temp ->
            HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = temp,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 2,
            )
        }

    private fun render(appliedDelta: Float?): ForecastDeltaDebug {
        var debug: ForecastDeltaDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours(),
            widthPx = 900,
            heightPx = 400,
            currentTime = start.plusHours(2).plusMinutes(25),
            observedAt = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            lastObservedTemp = 54f,
            appliedDelta = appliedDelta,
            useCelsius = false,
            onForecastDeltaPlaced = { debug = it },
        )
        return requireNotNull(debug) { "renderer must report a forecast-delta outcome" }
    }

    @Test
    fun `chinese caption is drawn 30 percent smaller than the value on one baseline`() {
        val debug = render(appliedDelta = -0.234f)

        assertEquals("label must actually be drawn. debug=$debug", "drawn", debug.reason)
        assertEquals("-0.2", debug.valueText)
        assertEquals(" 较预报", debug.suffixText)
        assertEquals("-0.2 较预报", debug.text)

        val valueSize = requireNotNull(debug.valueTextSizePx) { "drawn label must report the value size" }
        val suffixSize = requireNotNull(debug.suffixTextSizePx) { "drawn label must report the caption size" }
        assertEquals(
            "caption must run at SUFFIX_FONT_SCALE of the value size. debug=$debug",
            valueSize * ForecastDeltaLabel.SUFFIX_FONT_SCALE,
            suffixSize,
            0.01f,
        )

        // The caption is the localized resource, not the hardcoded English default.
        assertEquals("较预报", context.getString(R.string.forecast_delta_suffix))
        assertNotNull("drawn label must carry a placement box. debug=$debug", debug.box)
        assertNotNull(debug.centerX)
        assertNotNull(debug.baselineY)
    }

    @Test
    fun `reserved box matches the combined value plus caption ink width`() {
        val debug = render(appliedDelta = -0.234f)
        assertEquals("drawn", debug.reason)
        val box = requireNotNull(debug.box)

        // Rebuild the exact paints the renderer used (bitmapScale=1f -> labelScale=1f) and compare the
        // reserved width to the two-run ink. This is what keeps the empty-space finder honest after
        // the split: the old single-run width would no longer match what is drawn.
        val paints = TemperatureGraphStyle.ensurePaints(context, labelScale = 1f)
        val valueWidth = paints.stalenessTextPaint.measureText(requireNotNull(debug.valueText))
        val suffixPaint = android.graphics.Paint(paints.stalenessTextPaint).apply {
            textSize = paints.stalenessTextPaint.textSize * ForecastDeltaLabel.SUFFIX_FONT_SCALE
        }
        val suffixWidth = suffixPaint.measureText(requireNotNull(debug.suffixText))

        assertEquals(
            "box width must equal the combined run widths. debug=$debug",
            valueWidth + suffixWidth,
            box.width(),
            0.5f,
        )
        assertTrue("box must have positive width. debug=$debug", box.width() > 0f)
    }

    @Test
    fun `chinese label is suppressed when the delta rounds to zero`() {
        val debug = render(appliedDelta = 0f)

        assertEquals("no label for a zero delta. debug=$debug", "zero_delta", debug.reason)
        assertEquals(null, debug.box)
    }
}
