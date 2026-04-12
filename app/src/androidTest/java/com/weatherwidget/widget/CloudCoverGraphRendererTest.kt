package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class CloudCoverGraphRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // -------------------------------------------------------------------------
    // Helper to render and collect label placement debug records
    // -------------------------------------------------------------------------

    private fun render(
        covers: List<Int>,
        widthPx: Int = 800,
        heightPx: Int = 300,
        currentTime: LocalDateTime = LocalDateTime.of(2026, 3, 14, 10, 0),
    ): Triple<android.graphics.Bitmap, List<CloudCoverGraphRenderer.LabelPlacementDebug>, CloudCoverGraphRenderer.WatermarkPlacementDebug?> {
        val start = currentTime
        val hours = covers.mapIndexed { i, cover ->
            val dt = start.plusHours(i.toLong())
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = dt,
                cloudCover = cover,
                label = formatHour(dt.hour),
                isCurrentHour = i == 0,
                showLabel = true,
            )
        }
        val placements = mutableListOf<CloudCoverGraphRenderer.LabelPlacementDebug>()
        var watermark: CloudCoverGraphRenderer.WatermarkPlacementDebug? = null
        val bitmap = CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = currentTime,
            onLabelPlaced = { placements.add(it) },
            onWatermarkPlaced = { watermark = it },
        )
        return Triple(bitmap, placements, watermark)
    }

    // -------------------------------------------------------------------------
    // Bitmap dimension and crash tests
    // -------------------------------------------------------------------------

    @Test
    fun renderGraph_emptyHours_returnsEmptyBitmap() {
        val hours = emptyList<CloudCoverGraphRenderer.CloudHourData>()
        val bitmap = CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400,
            heightPx = 200,
            currentTime = LocalDateTime.now(),
        )
        assertNotNull(bitmap)
        assertEquals(400, bitmap.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun renderGraph_correctDimensions() {
        val (bitmap, _, _) = render(covers = List(24) { 50 }, widthPx = 1080, heightPx = 400)
        assertEquals(1080, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun renderGraph_allZeroCover_doesNotCrash() {
        val (bitmap, placements, _) = render(covers = List(24) { 0 })
        assertNotNull(bitmap)
        // Global min at 0% should be labeled
        assertTrue("Expected at least one label for flat-zero data", placements.isNotEmpty())
    }

    @Test
    fun renderGraph_allMaxCover_doesNotCrash() {
        val (bitmap, placements, _) = render(covers = List(24) { 100 })
        assertNotNull(bitmap)
        assertTrue("Expected at least one label for flat-100% data", placements.isNotEmpty())
    }

    @Test
    fun renderGraph_singleHour_doesNotCrash() {
        val (bitmap, _, _) = render(covers = listOf(75))
        assertNotNull(bitmap)
    }

    // -------------------------------------------------------------------------
    // Label placement: peak (global max) prefers above
    // -------------------------------------------------------------------------

    @Test
    fun highPeak_isPlacedAbove_whenEnoughRoom() {
        // Clear peak at 90% surrounded by low values — plenty of room above curve
        val covers = listOf(10, 10, 10, 90, 90, 90, 90, 90, 10, 10, 10)
        val (_, placements, _) = render(covers, widthPx = 1000, heightPx = 500)

        val peak = placements.find { it.isGlobalMax }
        assertNotNull("Expected the global max to be labeled. Placements=$placements", peak)
        assertTrue(
            "Global max (high cloud cover) should be placed above the curve when room exists. Placement=$peak",
            peak!!.placedAbove,
        )
    }

    

    // -------------------------------------------------------------------------
    // Label placement: non-peak low cover prefers below
    // -------------------------------------------------------------------------

    @Test
    fun globalMin_lowCover_isPlacedBelow() {
        // Sustained dip across 5 hours keeps the smoothed global minimum away from the edges,
        // with enough room on both sides of the curve. Non-peak labels should now try below first.
        val covers = listOf(80, 80, 20, 20, 20, 20, 20, 80, 80)
        val (_, placements, _) = render(covers, widthPx = 800, heightPx = 400)

        val minLabel = placements.find { it.isGlobalMin }
        assertNotNull("Expected global min to be labeled. Placements=$placements", minLabel)
        assertFalse(
            "Sustained low cloud cover should now prefer below the curve when room exists. Placement=$minLabel",
            minLabel!!.placedAbove,
        )
    }

    @Test
    fun risingEndLabel_prefersAbove() {
        val covers = listOf(60, 58, 40, 25, 15, 20, 28, 32)
        val (_, placements, _) = render(covers, widthPx = 900, heightPx = 420)

        val endLabel = placements.find { it.index == covers.lastIndex }
        assertNotNull("Expected final rising endpoint label to be drawn. Placements=$placements", endLabel)
        assertTrue(
            "Final rising endpoint should prefer above the curve. Placement=$endLabel",
            endLabel!!.placedAbove,
        )
    }

    @Test
    fun fallingEndLabel_prefersBelow() {
        val covers = listOf(20, 28, 35, 42, 40, 37, 34, 32)
        val (_, placements, _) = render(covers, widthPx = 900, heightPx = 420)

        val endLabel = placements.find { it.index == covers.lastIndex }
        assertNotNull("Expected final falling endpoint label to be drawn. Placements=$placements", endLabel)
        assertFalse(
            "Final falling endpoint should keep the default below-first behavior. Placement=$endLabel",
            endLabel!!.placedAbove,
        )
    }

    // -------------------------------------------------------------------------
    // Label values are clamped to 0-100
    // -------------------------------------------------------------------------

    @Test
    fun allLabelValues_areWithin0to100() {
        val covers = (0..23).map { (it * 5) % 101 }  // ramp through 0..100
        val (_, placements, _) = render(covers)
        for (p in placements) {
            assertTrue(
                "Cloud cover label value must be in 0..100, got ${p.cloudCover}",
                p.cloudCover in 0..100,
            )
        }
    }

    @Test
    fun watermark_fallsBackToAlternateLowRegion_whenLeftSideIsCrowded() {
        val covers = listOf(58, 55, 50, 54, 55, 66, 71, 62, 65, 73, 71, 80, 84)
        val (_, _, watermark) = render(
            covers = covers,
            widthPx = 624,
            heightPx = 325,
            currentTime = LocalDateTime.of(2026, 3, 30, 3, 0),
        )

        assertNotNull("Expected watermark placement debug callback", watermark)
        assertTrue(
            "Expected cloud watermark to find a fallback placement in a crowded graph. Debug=$watermark",
            watermark!!.placed,
        )
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private fun formatHour(hour24: Int): String {
        val h = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val suffix = if (hour24 < 12) "a" else "p"
        return "$h$suffix"
    }
}
