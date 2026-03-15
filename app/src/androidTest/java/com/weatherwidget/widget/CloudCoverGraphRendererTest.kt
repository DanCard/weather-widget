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
    ): Pair<android.graphics.Bitmap, List<CloudCoverGraphRenderer.LabelPlacementDebug>> {
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
        val bitmap = CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = currentTime,
            onLabelPlaced = { placements.add(it) },
        )
        return bitmap to placements
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
        val (bitmap, _) = render(covers = List(24) { 50 }, widthPx = 1080, heightPx = 400)
        assertEquals(1080, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun renderGraph_allZeroCover_doesNotCrash() {
        val (bitmap, placements) = render(covers = List(24) { 0 })
        assertNotNull(bitmap)
        // Global min at 0% should be labeled
        assertTrue("Expected at least one label for flat-zero data", placements.isNotEmpty())
    }

    @Test
    fun renderGraph_allMaxCover_doesNotCrash() {
        val (bitmap, placements) = render(covers = List(24) { 100 })
        assertNotNull(bitmap)
        assertTrue("Expected at least one label for flat-100% data", placements.isNotEmpty())
    }

    @Test
    fun renderGraph_singleHour_doesNotCrash() {
        val (bitmap, _) = render(covers = listOf(75))
        assertNotNull(bitmap)
    }

    // -------------------------------------------------------------------------
    // Label placement: peak (global max) prefers above
    // -------------------------------------------------------------------------

    @Test
    fun highPeak_isPlacedAbove_whenEnoughRoom() {
        // Clear peak at 90% surrounded by low values — plenty of room above curve
        val covers = listOf(10, 10, 10, 90, 90, 90, 90, 90, 10, 10, 10)
        val (_, placements) = render(covers, widthPx = 1000, heightPx = 500)

        val peak = placements.find { it.isGlobalMax }
        assertNotNull("Expected the global max to be labeled. Placements=$placements", peak)
        assertTrue(
            "Global max (high cloud cover) should be placed above the curve when room exists. Placement=$peak",
            peak!!.placedAbove,
        )
    }

    @Test
    fun highPeak_at100_isNotPlacedAbove_whenNoRoomAbove() {
        // 100% peak in a short bitmap — curve sits at the top, no room above the curve.
        // The label must either be placed below OR skipped entirely (both are correct).
        // At 200px height there is room below but not above for a 100% peak.
        val covers = listOf(0, 0, 0, 100, 100, 100, 100, 100, 0, 0, 0)
        val (_, placements) = render(covers, widthPx = 1000, heightPx = 200)

        val peak = placements.find { it.isGlobalMax }
        if (peak != null) {
            assertFalse(
                "100% peak with no room above must not be placed above the curve. Placement=$peak",
                peak.placedAbove,
            )
        }
        // If peak is null, the label was skipped (no room on either side) — also acceptable.
    }

    // -------------------------------------------------------------------------
    // Label placement: global min (low cover) prefers above
    // -------------------------------------------------------------------------

    @Test
    fun globalMin_lowCover_isPlacedAbove() {
        // Sustained dip across 5 hours: the center stays <=50 after 2 smoothing passes,
        // so preferAbove fires (rule: prob <= 50 → prefer above).
        // A single-point dip (e.g. [80,80,20,80,80]) would smooth to ~58% and miss the rule.
        val covers = listOf(80, 80, 20, 20, 20, 20, 20, 80, 80)
        val (_, placements) = render(covers, widthPx = 800, heightPx = 400)

        val minLabel = placements.find { it.isGlobalMin }
        assertNotNull("Expected global min to be labeled. Placements=$placements", minLabel)
        assertTrue(
            "Sustained low cloud cover (<=50% after smoothing) should prefer above. Placement=$minLabel",
            minLabel!!.placedAbove,
        )
    }

    // -------------------------------------------------------------------------
    // Label values are clamped to 0-100
    // -------------------------------------------------------------------------

    @Test
    fun allLabelValues_areWithin0to100() {
        val covers = (0..23).map { (it * 5) % 101 }  // ramp through 0..100
        val (_, placements) = render(covers)
        for (p in placements) {
            assertTrue(
                "Cloud cover label value must be in 0..100, got ${p.cloudCover}",
                p.cloudCover in 0..100,
            )
        }
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
