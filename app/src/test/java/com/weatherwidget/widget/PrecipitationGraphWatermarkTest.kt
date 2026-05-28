package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PrecipitationGraphWatermarkTest {

    private fun mockDpToPx(dp: Float): Float = dp
    private fun mockMeasureProbabilityText(text: String): Float = 20f
    private fun mockGetProbabilityTextBounds(text: String): Pair<Float, Float> = -10f to 2f
    private fun mockMeasureRainAmountText(text: String): Float = 30f
    private fun mockGetRainAmountTextBounds(text: String): Pair<Float, Float> = -12f to 3f

    private val mockTextMeasurer = PrecipitationGraphRenderer.TextMeasurer(
        measureProbabilityText = ::mockMeasureProbabilityText,
        getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
        measureRainAmountText = ::mockMeasureRainAmountText,
        getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
        measureActualRainAmountText = ::mockMeasureRainAmountText,
        getActualRainAmountTextBounds = ::mockGetRainAmountTextBounds,
        dpToPx = ::mockDpToPx,
        measureNowText = { 15f },
        getNowTextBounds = { -12f to 3f },
        measureDayText = { text, _ -> text.length * 8f },
        getDayTextBounds = { -10f to 2f },
    )

    private val mockTextMeasurerWithExtras = mockTextMeasurer.copy(
        measureNowText = { 24f },
        getNowTextBounds = { -10f to 2f },
        measureDayText = { text, _ -> text.length * 12f },
        getDayTextBounds = { -10f to 2f },
    )

    private fun makeHours(
        count: Int,
        baseProb: Int = 30,
        start: LocalDateTime = LocalDateTime.of(2026, 4, 7, 10, 0),
    ): List<PrecipitationGraphRenderer.PrecipHourData> {
        return (0 until count).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = baseProb,
                label = "${start.plusHours(i.toLong()).hour}h",
            )
        }
    }

    @Test
    fun `watermark prefers high position when no labels block`() {
        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = makeHours(5, baseProb = 20, start = start),
            widthPx = 500,
            heightPx = 300,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer
        )

        assertNotNull("Watermark should be placed", layout.watermarkPlacement)

        val placement = layout.watermarkPlacement!!
        assertEquals("Should place at top row (yFrac=0.12)", 0.12f, placement.yFrac, 0.001f)
    }

    @Test
    fun `watermark prefers left position when no labels block`() {
        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = makeHours(5, baseProb = 10, start = start),
            widthPx = 500,
            heightPx = 300,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer
        )

        assertNotNull("Watermark should be placed", layout.watermarkPlacement)

        val placement = layout.watermarkPlacement!!
        assertEquals("Should place at leftmost column (xFrac=0.15)", 0.15f, placement.xFrac, 0.001f)
    }

    @Test
    fun `watermark scans top-to-bottom left-to-right`() {
        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = makeHours(5, baseProb = 10, start = start),
            widthPx = 500,
            heightPx = 300,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer
        )

        val placement = layout.watermarkPlacement
        assertNotNull("Watermark should be placed", placement)
        assertTrue("yFrac should be low (high on screen), got ${placement!!.yFrac}", placement.yFrac <= 0.15f)
        assertTrue("xFrac should be low (left on screen), got ${placement.xFrac}", placement.xFrac <= 0.2f)
    }

    @Test
    fun `watermark still placed with high precipitation and many labels`() {
        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val hours = (0 until 25).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = if (i in 8..16) 97 else 5,
                label = "${start.plusHours(i.toLong()).hour}h",
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 731,
            heightPx = 308,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer
        )

        assertNotNull("Watermark should find a position even with many labels", layout.watermarkPlacement)
    }

    @Test
    fun `watermark not placed with fewer than 3 data points`() {
        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = makeHours(2, baseProb = 50, start = start),
            widthPx = 500,
            heightPx = 300,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer
        )

        assertNull("Watermark should NOT be placed with < 3 points", layout.watermarkPlacement)
    }

    @Test
    fun `watermark avoids now label and day labels`() {
        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val hours = (0 until 12).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = if (i in 3..8) 80 else 15,
                precipAmountMm = 0.8f,
                label = "${start.plusHours(i.toLong()).hour}h",
                isCurrentHour = i == 5,
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 600,
            heightPx = 300,
            currentTime = start.plusHours(5),
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurerWithExtras,
        )

        val watermark = layout.watermarkPlacement
        assertNotNull("Watermark should be placed", watermark)

        val watermarkBounds = PrecipitationGraphRenderer.PrecipRect(
            left = watermark!!.x,
            top = watermark.y,
            right = watermark.x + 24f,
            bottom = watermark.y + 24f,
        )

        layout.nowLabelPlacement?.let {
            assertTrue("Watermark should not overlap NOW label", !watermarkBounds.intersects(it.bounds))
        }
        layout.dayLabelPlacements.forEach { day ->
            val dayBounds = PrecipitationGraphRenderer.PrecipRect(
                left = day.x - 18f,
                top = day.y - 10f,
                right = day.x + 18f,
                bottom = day.y + 2f,
            )
            assertTrue("Watermark should not overlap ${day.side} day label", !watermarkBounds.intersects(dayBounds))
        }
    }
}
