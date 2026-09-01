package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PrecipitationGraphRendererTest {

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

    private val mockTextMeasurerWithNow = mockTextMeasurer.copy(
        measureNowText = { 24f },
        getNowTextBounds = { -10f to 2f },
    )

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
            textMeasurer = mockTextMeasurer
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
            textMeasurer = mockTextMeasurer
        )

        val placedLabels = layout.probabilityPlacements.map { it.debug }

        assertTrue("Peak at 80% should be labeled", placedLabels.any { it.probability == 80 })

        assertTrue("Start label at 0% should be present", placedLabels.any { it.index == 0 && it.probability == 0 })
        assertTrue("End label at 0% should be present", placedLabels.any { it.index == 10 && it.probability == 0 })

        val intermediateZeroLabels = placedLabels.filter { it.probability == 0 && it.index != 0 && it.index != 10 }
        assertTrue("Intermediate zero baseline should NOT be labeled. Placed: $placedLabels", intermediateZeroLabels.isEmpty())
    }

    @Test
    fun `renderGraph preserves right edge label through dense candidate filtering`() {
        val start = LocalDateTime.of(2026, 5, 5, 12, 0)
        val signal = listOf(6, 3, 3, 1, 3)

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${start.plusHours(i.toLong()).hour}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 700,
            heightPx = 320,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer,
        )

        assertNotNull(
            "Expected final right-edge precipitation label to survive filtering. Placed=${layout.probabilityPlacements.map { it.debug }}",
            layout.probabilityPlacements.find { it.debug.index == signal.lastIndex },
        )
    }

    @Test
    fun `renderGraph adds midpoint label when only edge anchors survive`() {
        val start = LocalDateTime.of(2026, 5, 5, 12, 0)
        val signal = listOf(60, 55, 50, 45, 40, 35, 30)

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${start.plusHours(i.toLong()).hour}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 900,
            heightPx = 360,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer,
        )

        assertEquals(
            "Expected edge anchors plus one midpoint label when only edge candidates remain",
            listOf(0, 3, signal.lastIndex),
            layout.probabilityPlacements.map { it.debug.index },
        )
    }

    @Test
    fun `renderGraph skips midpoint backfill when center value matches edge label`() {
        val start = LocalDateTime.of(2026, 5, 5, 12, 0)
        val signal = listOf(50, 50, 50, 50, 50, 50, 50)

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${start.plusHours(i.toLong()).hour}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 900,
            heightPx = 360,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer,
        )

        assertEquals(
            "Expected no midpoint backfill when the center value duplicates an edge value",
            listOf(0, signal.lastIndex),
            layout.probabilityPlacements.map { it.debug.index },
        )
    }

    @Test
    fun `renderGraph rising end label prefers above`() {
        val start = LocalDateTime.of(2026, 5, 5, 12, 0)
        val signal = listOf(60, 58, 40, 25, 15, 20, 28, 32)

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${start.plusHours(i.toLong()).hour}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 900,
            heightPx = 420,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer,
        )

        val endLabel = layout.probabilityPlacements.find { it.debug.index == signal.lastIndex }
        assertNotNull("Expected final rising endpoint label to be drawn", endLabel)
        assertTrue("Expected rising end label to prefer above", endLabel!!.debug.placedAbove)
    }

    @Test
    fun `renderGraph falling end label prefers below`() {
        val start = LocalDateTime.of(2026, 5, 5, 12, 0)
        val signal = listOf(20, 28, 35, 42, 40, 37, 34, 32)

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${start.plusHours(i.toLong()).hour}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 900,
            heightPx = 420,
            currentTime = start,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer,
        )

        val endLabel = layout.probabilityPlacements.find { it.debug.index == signal.lastIndex }
        assertNotNull("Expected final falling endpoint label to be drawn", endLabel)
        assertFalse("Expected falling end label to prefer below", endLabel!!.debug.placedAbove)
    }

    @Test
    fun `renderGraph low right edge label avoids weather icon by falling back above`() {
        val start = LocalDateTime.of(2026, 5, 5, 12, 0)
        val signal = listOf(22, 18, 12, 6, 3, 1)

        val hours = signal.mapIndexed { i, prob ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = prob,
                label = "${start.plusHours(i.toLong()).hour}h",
                iconRes = com.weatherwidget.R.drawable.ic_weather_partly_cloudy,
                isSunny = true,
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 584,
            heightPx = 385,
            currentTime = start,
            showHourlyIcons = true,
            footerIconSize = 21f, // ~hour-label text height * FOOTER_ICON_TO_TEXT_RATIO under identity mockDpToPx
            textMeasurer = mockTextMeasurer,
        )

        val endLabel = layout.probabilityPlacements.find { it.debug.index == signal.lastIndex }
        assertNotNull("Expected final low endpoint label to be drawn", endLabel)
        assertTrue(
            "Expected low right-edge label to avoid the icon by drawing above the curve. Placement=${endLabel!!.debug}",
            endLabel.debug.placedAbove,
        )
        assertTrue(
            "Expected low right-edge label to clear the icon band by a visible margin. bounds=${endLabel.bounds} graphBottom=${layout.graphBottom}",
            endLabel.bounds.bottom < layout.graphBottom - 4f,
        )
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
            textMeasurer = mockTextMeasurer
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
            textMeasurer = mockTextMeasurer
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
            textMeasurer = mockTextMeasurer
        )

        assertTrue(
            "Should NOT place rain amount when precipAmountMm is null",
            layout.rainAmountPlacements.isEmpty(),
        )
    }

    @Test
    fun `renderGraph places predicted and actual rain amount labels`() {
        val start = LocalDateTime.of(2026, 4, 10, 6, 0)
        val hours = (0 until 12).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 70,
                precipAmountMm = 1.0f,
                actualPrecipAmountMm = if (i in 2..5) 0.5f else null,
                label = "${(start.plusHours(i.toLong()).hour)}h",
                showLabel = true,
            )
        }

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 900,
            heightPx = 420,
            currentTime = start.plusHours(6),
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer,
        )

        assertTrue(
            "Should place predicted rain amount label",
            layout.rainAmountPlacements.any { it.text.startsWith("Pred ") },
        )
        assertTrue(
            "Should place actual rain amount label",
            layout.actualRainAmountPlacements.any { it.text.startsWith("Act ") },
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
            textMeasurer = mockTextMeasurer
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
            textMeasurer = mockTextMeasurer
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
            textMeasurer = mockTextMeasurer
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
            textMeasurer = mockTextMeasurerWithNow,
        )

        val nowBounds = layout.nowLabelPlacement?.bounds
        val rainBounds = layout.rainAmountPlacements.firstOrNull()?.bounds

        assertNotNull("Expected NOW label placement", nowBounds)
        assertNotNull("Expected rain amount placement", rainBounds)
        assertTrue("NOW and rain amount labels should not overlap", !nowBounds!!.intersects(rainBounds!!))
    }

    // --- Day/night (WIDE) and per-hour (NARROW) rain labels ---

    /** Builds an hourly series starting at midnight with per-hour predicted/actual rain. */
    private fun dayNightHours(
        predByHour: (Int) -> Float?,
        actualByHour: (Int) -> Float? = { null },
        count: Int = 24,
    ): List<PrecipitationGraphRenderer.PrecipHourData> {
        val start = LocalDateTime.of(2026, 5, 20, 0, 0)
        return (0 until count).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 60,
                precipAmountMm = predByHour(i),
                actualPrecipAmountMm = actualByHour(i),
                label = "${start.plusHours(i.toLong()).hour}h",
                showLabel = true,
            )
        }
    }

    @Test
    fun `dayNightRuns splits a midnight-aligned day into night-day-night`() {
        val hours = dayNightHours(predByHour = { 1f })
        val runs = PrecipitationGraphRenderer.dayNightRuns(hours)

        // 0-7 night, 8-19 day, 20-23 night
        assertEquals(3, runs.size)
        assertEquals(Triple(0, 7, false), Triple(runs[0].startIndex, runs[0].endIndex, runs[0].isDay))
        assertEquals(Triple(8, 19, true), Triple(runs[1].startIndex, runs[1].endIndex, runs[1].isDay))
        assertEquals(Triple(20, 23, false), Triple(runs[2].startIndex, runs[2].endIndex, runs[2].isDay))
    }

    @Test
    fun `computeDayNightBoundaryXs marks the 8a and 8p transitions`() {
        val hours = dayNightHours(predByHour = { 1f })
        val boundaries = PrecipitationGraphRenderer.computeDayNightBoundaryXs(hours, hourWidth = 10f)

        // Boundaries at the first hour of each new phase: index 8 (8a) and index 20 (8p).
        assertEquals(listOf(80f, 200f), boundaries)
    }

    @Test
    fun `selectDayNightSegments picks wettest day and wettest night only`() {
        // Rain at 9a-10a (day) and 22:00 (a single night hour).
        val hours = dayNightHours(
            predByHour = { i -> if (i in 9..10) 3f else if (i == 22) 5f else 0f },
        )
        val segments = PrecipitationGraphRenderer.selectDayNightSegments(hours)

        assertEquals("Expected at most one day + one night segment", 2, segments.size)
        assertTrue("Day segment covers the daytime run", segments.any { it.isDay && 9 in it.startIndex..it.endIndex })
        assertTrue("Night segment covers the late-night run", segments.any { !it.isDay && 22 in it.startIndex..it.endIndex })
    }

    @Test
    fun `DAY_NIGHT layout anchors pred and actual labels and draws dividers`() {
        val hours = dayNightHours(
            predByHour = { i -> if (i in 9..11) 2f else 0f },
            actualByHour = { i -> if (i in 9..11) 1f else null },
        )

        val layout = PrecipitationGraphRenderer.calculateLayout(
            hours = hours,
            widthPx = 1000,
            heightPx = 420,
            currentTime = LocalDateTime.of(2026, 5, 21, 0, 0),
            rainLabelMode = com.weatherwidget.shared.graph.RainPeriodSelection.Mode.DAY_NIGHT,
            showHourlyIcons = false,
            textMeasurer = mockTextMeasurer,
        )

        assertTrue("Pred label present", layout.rainAmountPlacements.any { it.text.startsWith("Pred ") })
        assertTrue("Act label present", layout.actualRainAmountPlacements.any { it.text.startsWith("Act ") })
        assertEquals("Two day/night dividers expected", 2, layout.dayNightBoundaryXs.size)
    }

    @Test
    fun `PER_HOUR layout labels only the first four hours where rain exists`() {
        // Window: index 0 dry, 1 pred-only, 2 pred+act, 3 dry, 4 (clipped) has rain but is excluded.
        val start = LocalDateTime.of(2026, 5, 20, 6, 0)
        val hours = (0 until 5).map { i ->
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(i.toLong()),
                precipProbability = 50,
                precipAmountMm = when (i) { 1 -> 2f; 2 -> 3f; 4 -> 9f; else -> 0f },
                actualPrecipAmountMm = if (i == 2) 1f else null,
                label = "${start.plusHours(i.toLong()).hour}h",
                showLabel = true,
            )
        }

        val predPeriods = PrecipitationGraphRenderer.perHourRainPeriods(hours, hourWidth = 10f) { it.precipAmountMm }
        val actualPeriods = PrecipitationGraphRenderer.perHourRainPeriods(hours, hourWidth = 10f) { it.actualPrecipAmountMm }

        assertEquals("Pred at hours 1 and 2 only (hour 4 is clipped)", listOf(1, 2), predPeriods.map { it.startIndex })
        assertEquals("Act at hour 2 only", listOf(2), actualPeriods.map { it.startIndex })
        // Each period is a single hour anchored to its column.
        assertTrue("Pred periods are single-hour", predPeriods.all { it.startIndex == it.endIndex })
        assertEquals("Pred hour-1 anchored to its x", 10f, predPeriods.first().anchorX)
    }
}
