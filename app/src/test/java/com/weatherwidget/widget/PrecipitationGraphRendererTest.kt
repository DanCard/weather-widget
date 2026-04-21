package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import android.graphics.RectF

@Category(LongDuration::class)
class PrecipitationGraphRendererTest {

    private fun mockDpToPx(dp: Float): Float = dp
    private fun mockMeasureProbabilityText(text: String): Float = 20f
    private fun mockGetProbabilityTextBounds(text: String): Pair<Float, Float> = -10f to 2f
    private fun mockMeasureRainAmountText(text: String): Float = 30f
    private fun mockGetRainAmountTextBounds(text: String): Pair<Float, Float> = -12f to 3f

    @Test
    fun `renderGraph thins out non-peak labels on a monotonic rise`() {
        val start = LocalDateTime.of(2026, 2, 17, 2, 0)
        val signal = listOf(
            78, 81, 87, 90, 91, 92, 94, 96, 93, 83, 71, 63, 57, 54, 61, 74, 81, 77, 70, 64, 57, 51, 45, 46, 51
        )

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        val placedLabels = layout.probabilityPlacements.map { it.debug }

        val morningHighLabel = placedLabels.find { it.index == 4 }
        assertNull("Index 4 (6 AM, 91%) should NOT be labeled after the fix. Placed: ${placedLabels.map { "${it.index}(${it.probability}%)" }}", morningHighLabel)

        assertTrue("Global max at index 7 should be labeled", placedLabels.any { it.index == 7 && it.probability == 96 })

        assertTrue("Peaks should be placed above the line", placedLabels.filter { it.isPeak }.all { it.placedAbove })
        assertTrue("Deep valleys should be placed below the line", placedLabels.filter { it.isValley && it.probability > 15 }.all { !it.placedAbove })
    }

    @Test
    fun `renderGraph labels peak but skips flat zero baseline`() {
        val start = LocalDateTime.of(2026, 2, 17, 2, 0)
        val signal = listOf(0, 0, 0, 0, 40, 80, 45, 0, 0, 0, 0)

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        val placedLabels = layout.probabilityPlacements.map { it.debug }

        assertTrue("Peak at 80% should be labeled", placedLabels.any { it.probability == 80 })

        assertTrue("Start label at 0% should be present", placedLabels.any { it.index == 0 && it.probability == 0 })
        assertTrue("End label at 0% should be present", placedLabels.any { it.index == 10 && it.probability == 0 })

        val intermediateZeroLabels = placedLabels.filter { it.probability == 0 && it.index != 0 && it.index != 10 }
        assertTrue("Intermediate zero baseline should NOT be labeled. Placed: $placedLabels", intermediateZeroLabels.isEmpty())
    }

    // --- Rain amount annotation tests ---

    @Test
    fun `renderGraph shows rain amount for 99+ percent block`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val hours = (0 until 12).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 100,
                precipAmountMm = 1.0f,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        assertTrue(
            "Should place rain amount label for 99%+ block",
            layout.rainAmountPlacements.isNotEmpty(),
        )
    }

    @Test
    fun `renderGraph skips rain amount when below 99 percent`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val hours = (0 until 12).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 98,
                precipAmountMm = 2.0f,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        assertTrue(
            "Should NOT place rain amount when prob < 99%",
            layout.rainAmountPlacements.isEmpty(),
        )
    }

    @Test
    fun `renderGraph skips rain amount when precipAmountMm is null`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val hours = (0 until 12).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 100,
                precipAmountMm = null,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        assertTrue(
            "Should NOT place rain amount when precipAmountMm is null",
            layout.rainAmountPlacements.isEmpty(),
        )
    }

    @Test
    fun `renderGraph skips rain amount when total is zero`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val hours = (0 until 12).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 100,
                precipAmountMm = 0.0f,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        assertTrue(
            "Should NOT place rain amount when total is 0",
            layout.rainAmountPlacements.isEmpty(),
        )
    }

    @Test
    fun `renderGraph handles two separate 99+ blocks`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        // Two blocks of 100% separated by a 50% gap
        val probs = listOf(100, 100, 100, 50, 50, 100, 100, 100)
        val hours = probs.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                precipAmountMm = 2.0f,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        assertEquals(
            "Should place two rain amount labels for two separate blocks",
            2,
            layout.rainAmountPlacements.size,
        )
    }

    @Test
    fun `renderGraph single hour 99+ block omits time range`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val probs = listOf(0, 0, 100, 0, 0)
        val hours = probs.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                precipAmountMm = if (prob >= 99) 5.0f else 0.0f,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx
        )

        assertTrue(
            "Should place rain amount for single-hour block",
            layout.rainAmountPlacements.isNotEmpty(),
        )
        // Single hour: label should NOT contain a dash (time range)
        val label = layout.rainAmountPlacements.first().text
        assertTrue(
            "Single-hour label should not contain a time range dash, got: $label",
            !label.contains("-"),
        )
    }
}
