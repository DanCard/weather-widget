package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class PrecipitationGraphRendererTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        io.mockk.mockkStatic(android.graphics.Bitmap::class)
        io.mockk.mockkConstructor(android.graphics.Canvas::class)
        io.mockk.mockkConstructor(android.graphics.Paint::class)

        val bitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true)
        io.mockk.every {
            android.graphics.Bitmap.createBitmap(any<Int>(), any<Int>(), any<android.graphics.Bitmap.Config>())
        } returns bitmap
        io.mockk.every { anyConstructed<android.graphics.Canvas>().drawText(any<String>(), any(), any(), any()) } returns Unit
        io.mockk.every { anyConstructed<android.graphics.Canvas>().drawPath(any(), any()) } returns Unit

        io.mockk.every { anyConstructed<android.graphics.Paint>().measureText(any<String>()) } returns 20f
        val mockFontMetrics = android.graphics.Paint.FontMetrics().apply {
            ascent = -10f
            descent = 2f
        }
        io.mockk.every { anyConstructed<android.graphics.Paint>().fontMetrics } returns mockFontMetrics
        io.mockk.every { anyConstructed<android.graphics.Paint>().textSize } returns 12f

        context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val resources = io.mockk.mockk<android.content.res.Resources>(relaxed = true)
        val metrics = android.util.DisplayMetrics().apply { density = 1.0f }
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.displayMetrics } returns metrics
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    // --- Existing label placement tests ---

    @Test
    fun `shouldShowHourlyIcons is true for wide graph`() {
    }

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

        val placedLabels = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placedLabels.add(it) }
        )

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

        val placedLabels = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placedLabels.add(it) }
        )

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

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "Should place rain amount label for 99%+ block, logs=$debugLogs",
            placed.isNotEmpty(),
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

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "Should NOT place rain amount when prob < 99%, logs=$debugLogs",
            placed.isEmpty(),
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

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "Should NOT place rain amount when precipAmountMm is null, logs=$debugLogs",
            placed.isEmpty(),
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

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "Should NOT place rain amount when total is 0, logs=$debugLogs",
            placed.isEmpty(),
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

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertEquals(
            "Should place two rain amount labels for two separate blocks, logs=$debugLogs",
            2,
            placed.size,
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

        val debugLogs = mutableListOf<String>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onDebugLog = { debugLogs.add(it) },
        )

        val placed = debugLogs.filter { it.startsWith("rainAmountPlaced") }
        assertTrue(
            "Should place rain amount for single-hour block, logs=$debugLogs",
            placed.isNotEmpty(),
        )
        // Single hour: label should NOT contain a dash (time range)
        val label = placed.first()
        assertTrue(
            "Single-hour label should not contain a time range dash, got: $label",
            !label.substringAfter("\"").substringBefore("\"").contains("-"),
        )
    }
}
