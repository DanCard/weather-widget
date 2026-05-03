package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

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
    fun `renderGraph shows rain amount for visible graph window`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val hours = (0 until 12).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 40,
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
            "Should place one rain amount label for the visible graph window",
            layout.rainAmountPlacements.isNotEmpty(),
        )
    }

    @Test
    fun `renderGraph does not require high probability for visible graph total`() {
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
            "Visible-window rain total should still be shown when precipitation amounts are present",
            layout.rainAmountPlacements.isNotEmpty(),
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
    fun `renderGraph collapses separate rain blocks into one visible-window amount`() {
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
            "Should place one visible-window rain amount label",
            1,
            layout.rainAmountPlacements.size,
        )
    }

    @Test
    fun `renderGraph single hour rain amount omits time range`() {
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

    @Test
    fun `renderGraph fixed window override still chooses best window`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val amounts = listOf(0f, 1f, 4f, 5f, 1f, 0f)
        val hours = amounts.mapIndexed { i, amount ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 40,
                precipAmountMm = amount,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val periods = PrecipitationGraphRenderer.findFixedWindowRainPeriods(hours, windowHours = 3)

        assertEquals(1, periods.size)
        assertEquals(10f, periods.first().totalAmountMm)
    }

    @Test
    fun `renderGraph places now label before rain amount overlap`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val hours = (0 until 8).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = if (i in 2..5) 85 else 20,
                precipAmountMm = 1.5f,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                isCurrentHour = i == 3,
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 600,
            heightPx = 300,
            currentTime = start.plusHours(3),
            showHourlyIcons = false,
            measureProbabilityText = ::mockMeasureProbabilityText,
            getProbabilityTextBounds = ::mockGetProbabilityTextBounds,
            measureRainAmountText = ::mockMeasureRainAmountText,
            getRainAmountTextBounds = ::mockGetRainAmountTextBounds,
            dpToPx = ::mockDpToPx,
            measureNowText = { 24f },
            getNowTextBounds = { -10f to 2f },
        )

        val nowBounds = layout.nowLabelPlacement?.bounds
        val rainBounds = layout.rainAmountPlacements.firstOrNull()?.bounds

        assertNotNull("Expected NOW label placement", nowBounds)
        assertNotNull("Expected rain amount placement", rainBounds)
        assertTrue("NOW and rain amount labels should not overlap", !nowBounds!!.intersects(rainBounds!!))
    }
}
