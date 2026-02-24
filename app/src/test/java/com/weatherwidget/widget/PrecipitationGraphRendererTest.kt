package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PrecipitationGraphRendererTest {
    @Test
    fun `shouldShowHourlyIcons is true for wide graph`() {
        assertTrue(PrecipitationGraphRenderer.shouldShowHourlyIcons(584))
    }

    @Test
    fun `shouldShowHourlyIcons is false for narrow graph`() {
        assertTrue(!PrecipitationGraphRenderer.shouldShowHourlyIcons(360))
    }

    @Test
    fun `iconStrideForLabelSpacing uses denser icons in wide zoom`() {
        assertEquals(1, PrecipitationGraphRenderer.iconStrideForLabelSpacing(28f))
    }

    @Test
    fun `iconStrideForLabelSpacing keeps hourly icons in narrow zoom`() {
        assertEquals(1, PrecipitationGraphRenderer.iconStrideForLabelSpacing(18f))
    }

    @Test
    fun `computeEndLabelPlacement anchors label near right edge`() {
        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 100f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = emptyList(),
            )

        assertNotNull(placement)
        assertEquals(272f, placement!!.x, 0.001f)
        assertTrue(!placement.usedFallback)
    }

    @Test
    fun `computeEndLabelPlacement clamps preferred baseline to top safe bound`() {
        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 5f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = emptyList(),
            )

        assertNotNull(placement)
        assertEquals(22f, placement!!.baselineY, 0.001f)
    }

    @Test
    fun `computeEndLabelPlacement uses fallback when preferred overlaps`() {
        val preferredBounds =
            PrecipitationGraphRenderer.PlacementRect(
                left = 252f,
                top = 70f,
                right = 292f,
                bottom = 93f,
            )

        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 100f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = listOf(preferredBounds),
            )

        assertNotNull(placement)
        assertTrue(placement!!.usedFallback)
        assertEquals(114f, placement.baselineY, 0.001f)
    }

    // --- bilateralProminence tests ---

    @Test
    fun `bilateralProminence returns high value for true valley`() {
        // True valley: 80, 50, 85 → left diff=30, right diff=35 → min=30
        val values = listOf(80, 50, 85)
        val result = PrecipitationGraphRenderer.bilateralProminence(values, 1)
        assertEquals(30, result)
    }

    @Test
    fun `bilateralProminence returns low value for one-sided valley`() {
        // One-sided valley on monotonic slope: 31, 32, 50 → left diff=1, right diff=18 → min=1
        val values = listOf(31, 32, 50)
        val result = PrecipitationGraphRenderer.bilateralProminence(values, 1)
        assertEquals(1, result)
    }

    @Test
    fun `bilateralProminence returns 0 for edge indices`() {
        val values = listOf(10, 20, 30, 40)
        assertEquals(0, PrecipitationGraphRenderer.bilateralProminence(values, 0))
        assertEquals(0, PrecipitationGraphRenderer.bilateralProminence(values, 3))
    }

    @Test
    fun `renderGraph thins out non-peak labels on a monotonic rise`() {
        // Mock static Bitmap and Canvas
        io.mockk.mockkStatic(android.graphics.Bitmap::class)
        io.mockk.mockkConstructor(android.graphics.Canvas::class)
        io.mockk.mockkConstructor(android.graphics.Paint::class)
        
        val bitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true)
        io.mockk.every { android.graphics.Bitmap.createBitmap(any<Int>(), any<Int>(), any<android.graphics.Bitmap.Config>()) } returns bitmap
        io.mockk.every { anyConstructed<android.graphics.Canvas>().drawText(any<String>(), any(), any(), any()) } returns Unit
        io.mockk.every { anyConstructed<android.graphics.Canvas>().drawPath(any(), any()) } returns Unit
        
        // Ensure labels have size so overlap logic works (but not too much overlap)
        io.mockk.every { anyConstructed<android.graphics.Paint>().measureText(any<String>()) } returns 20f
        io.mockk.every { anyConstructed<android.graphics.Paint>().textSize } returns 12f

        // Need a MockContext for the dpToPx call
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val resources = io.mockk.mockk<android.content.res.Resources>(relaxed = true)
        val metrics = android.util.DisplayMetrics().apply { density = 1.0f }
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.displayMetrics } returns metrics

        val start = LocalDateTime.of(2026, 2, 17, 2, 0)
        // Exact signal from Samsung logs
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
        
        // This will call the actual renderGraph logic but mock the Bitmap creation
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placedLabels.add(it) }
        )

        // VERIFY: morning high label at index 4 should now be GONE
        val morningHighLabel = placedLabels.find { it.index == 4 }
        assertNull("Index 4 (6 AM, 91%) should NOT be labeled after the fix. Placed: ${placedLabels.map { "${it.index}(${it.probability}%)" }}", morningHighLabel)

        // Verify other important labels are still there
        assertTrue("Global max at index 6 should be labeled", placedLabels.any { it.index == 6 && it.probability == 94 })
        assertTrue("Start anchor at index 0 should be labeled", placedLabels.any { it.index == 0 && it.probability == 80 })

        io.mockk.unmockkAll()
    }

    @Test
    fun `computeEndLabelPlacement returns null when preferred and fallback overlap`() {
        val preferredBounds =
            PrecipitationGraphRenderer.PlacementRect(
                left = 252f,
                top = 76f,
                right = 292f,
                bottom = 96f,
            )
        val fallbackBounds =
            PrecipitationGraphRenderer.PlacementRect(
                left = 252f,
                top = 94f,
                right = 292f,
                bottom = 114f,
            )

        val placement =
            PrecipitationGraphRenderer.computeEndLabelPlacement(
                textWidth = 40f,
                textHeight = 20f,
                widthPx = 300,
                graphBottom = 200f,
                pointY = 100f,
                aboveGap = 4f,
                belowGap = 14f,
                rightPadding = 8f,
                verticalInset = 2f,
                existingBounds = listOf(preferredBounds, fallbackBounds),
            )

        assertNull(placement)
    }
}
