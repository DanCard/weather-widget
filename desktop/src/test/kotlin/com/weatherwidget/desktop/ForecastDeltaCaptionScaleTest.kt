package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.ForecastDeltaLabel
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The desktop half of the hourly `-0.2 from forecast` label: the value and its caption must be two
 * runs on one baseline, with the caption 30% smaller. `buildDeltaLabelAnnotatedString` is the exact
 * string the graph draws, so asserting the spans here pins the desktop rendering without a Compose
 * render (Android covers the same split in `ForecastDeltaLabelChineseIntegrationRoboTest`).
 */
@Category(ShortDuration::class)
class ForecastDeltaCaptionScaleTest {

    @Test
    fun `caption span is 30 percent smaller than the value span`() {
        val annotated = buildDeltaLabelAnnotatedString(value = "-0.2", suffix = " from forecast", scale = 1f)

        assertEquals("-0.2 from forecast", annotated.text)
        val spans = annotated.spanStyles
        assertEquals("exactly two runs: value then caption", 2, spans.size)

        val valueSpan = spans[0]
        val captionSpan = spans[1]
        assertEquals("-0.2", annotated.text.substring(valueSpan.start, valueSpan.end))
        assertEquals(" from forecast", annotated.text.substring(captionSpan.start, captionSpan.end))

        val valueSize = requireNotNull(valueSpan.item.fontSize) { "value span must carry a font size" }.value
        val captionSize = requireNotNull(captionSpan.item.fontSize) { "caption span must carry a font size" }.value
        assertEquals(
            "caption must run at SUFFIX_FONT_SCALE of the value size",
            ForecastDeltaLabel.SUFFIX_FONT_SCALE,
            captionSize / valueSize,
            0.0001f,
        )
    }

    @Test
    fun `both runs scale with the render scale`() {
        val annotated = buildDeltaLabelAnnotatedString(value = "+1.2", suffix = " from forecast", scale = 2f)
        val spans = annotated.spanStyles
        val valueSize = requireNotNull(spans[0].item.fontSize).value
        val captionSize = requireNotNull(spans[1].item.fontSize).value

        // Doubling the render scale doubles both runs; the 30% relationship is scale-invariant.
        assertEquals(22.5f, valueSize, 0.0001f)
        assertEquals(15.75f, captionSize, 0.0001f)
    }
}
