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
class PrecipitationGraphWatermarkTest {

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
        val watermarkPlacements = mutableListOf<PrecipitationGraphRenderer.WatermarkPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = makeHours(5, baseProb = 20),
            widthPx = 500,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 4, 7, 10, 0),
            onWatermarkPlaced = { watermarkPlacements.add(it) },
        )

        assertNotNull("Watermark should be placed", watermarkPlacements.firstOrNull())

        val placement = watermarkPlacements.first()
        assertEquals("Should place at top row (yFrac=0.12)", 0.12f, placement.yFrac, 0.001f)
    }

    @Test
    fun `watermark prefers left position when no labels block`() {
        val watermarkPlacements = mutableListOf<PrecipitationGraphRenderer.WatermarkPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = makeHours(5, baseProb = 10),
            widthPx = 500,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 4, 7, 10, 0),
            onWatermarkPlaced = { watermarkPlacements.add(it) },
        )

        assertNotNull("Watermark should be placed", watermarkPlacements.firstOrNull())

        val placement = watermarkPlacements.first()
        assertEquals("Should place at leftmost column (xFrac=0.15)", 0.15f, placement.xFrac, 0.001f)
    }

    @Test
    fun `watermark scans top-to-bottom left-to-right`() {
        val watermarkPlacements = mutableListOf<PrecipitationGraphRenderer.WatermarkPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = makeHours(5, baseProb = 10),
            widthPx = 500,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 4, 7, 10, 0),
            onWatermarkPlaced = { watermarkPlacements.add(it) },
        )

        val placement = watermarkPlacements.firstOrNull()
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
        val watermarkPlacements = mutableListOf<PrecipitationGraphRenderer.WatermarkPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 731,
            heightPx = 308,
            currentTime = start,
            onWatermarkPlaced = { watermarkPlacements.add(it) },
        )

        assertNotNull("Watermark should find a position even with many labels", watermarkPlacements.firstOrNull())
    }

    @Test
    fun `watermark not placed with fewer than 3 data points`() {
        val watermarkPlacements = mutableListOf<PrecipitationGraphRenderer.WatermarkPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = makeHours(2, baseProb = 50),
            widthPx = 500,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 4, 7, 10, 0),
            onWatermarkPlaced = { watermarkPlacements.add(it) },
        )

        assertNull("Watermark should NOT be placed with < 3 points", watermarkPlacements.firstOrNull())
    }
}