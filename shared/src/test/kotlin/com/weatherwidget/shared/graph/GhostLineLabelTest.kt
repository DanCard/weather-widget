package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostLineLabelTest {

    // A flat curve sitting near the bottom of the plot, leaving the top band empty.
    private val lowCurve: (Float) -> Float? = { 180f }
    private val metrics = GhostLineLabel.Metrics(width = 40f, ascent = -10f, descent = 4f) // height 14
    private val plot = GraphRect(0f, 0f, 400f, 200f)

    /** A clear hour point near the top of the plot (well above the low curve), in the right half. */
    private fun rightCandidate(x: Float, temp: Float = 69.4f, hasHourLabel: Boolean = true) =
        GhostLineLabel.Candidate(x = x, ghostY = 60f, expectedTemp = temp, hasHourLabel = hasHourLabel)

    private fun place(
        candidates: List<GhostLineLabel.Candidate>,
        spanHours: Long = 6,
        drawnBounds: List<GraphRect> = emptyList(),
        curveYAt: (Float) -> Float? = lowCurve,
    ) = GhostLineLabel.place(
        candidates = candidates,
        spanHours = spanHours,
        plot = plot,
        drawnBounds = drawnBounds,
        curveYAt = curveYAt,
        metrics = metrics,
        padPx = 4f,
        gapPx = 3f,
    )

    @Test
    fun `format shows one decimal with degree`() {
        assertEquals("69.4°", GhostLineLabel.format(69.4f))
        assertEquals("69.0°", GhostLineLabel.format(69f))
        assertEquals("70.0°", GhostLineLabel.format(69.96f)) // rounds up at the tenths
        assertEquals("0.0°", GhostLineLabel.format(-0.04f))  // rounds to 0, no "-0.0"
        assertEquals("-1.2°", GhostLineLabel.format(-1.24f))
    }

    @Test
    fun `places a label hugging the line at a clear right-half hour`() {
        val p = place(listOf(rightCandidate(300f)))
        assertNotNull(p)
        assertEquals("69.4°", p!!.text)
        // Sits above the ghost line (y=60) by the gap, clear of the low curve.
        assertTrue(p.box.bottom <= 60f)
        assertTrue(p.box.bottom < 180f)
        assertEquals(p.box.top + 10f, p.baselineY, 0.001f) // baseline below top by |ascent|
    }

    @Test
    fun `suppressed past the narrow-view span max`() {
        assertNotNull(place(listOf(rightCandidate(300f)), spanHours = GhostLineLabel.MAX_HOURS_SPAN))
        assertNull(place(listOf(rightCandidate(300f)), spanHours = GhostLineLabel.MAX_HOURS_SPAN + 1))
    }

    @Test
    fun `ignores left-half hours, only labels the right half`() {
        // Only a left-half candidate (x below the 200 midpoint): nothing to place.
        assertNull(place(listOf(rightCandidate(80f))))
        // A right-half candidate is placed at its x.
        val p = place(listOf(rightCandidate(300f)))
        assertNotNull(p)
        assertEquals(300f, p!!.centerX, 0.001f)
    }

    @Test
    fun `prefers an hour with a footer label over an unlabeled one`() {
        val unlabeled = rightCandidate(260f, temp = 60f, hasHourLabel = false)
        val labeled = rightCandidate(340f, temp = 70f, hasHourLabel = true)
        val p = place(listOf(unlabeled, labeled))
        assertNotNull(p)
        assertEquals(340f, p!!.centerX, 0.001f)
        assertEquals("70.0°", p.text)
    }

    @Test
    fun `falls back to an unlabeled hour when the labeled one is blocked`() {
        val unlabeled = rightCandidate(260f, temp = 60f, hasHourLabel = false)
        val labeled = rightCandidate(340f, temp = 70f, hasHourLabel = true)
        // Block both above- and below-line boxes at the labeled hour's x.
        val blocker = GraphRect(300f, 0f, 400f, 200f)
        val p = place(listOf(unlabeled, labeled), drawnBounds = listOf(blocker))
        assertNotNull(p)
        assertEquals(260f, p!!.centerX, 0.001f)
        assertEquals("60.0°", p.text)
    }

    @Test
    fun `null when every right-half hour collides with an existing label`() {
        val blocker = GraphRect(200f, 0f, 400f, 200f) // covers the entire right half
        assertNull(place(listOf(rightCandidate(260f), rightCandidate(340f)), drawnBounds = listOf(blocker)))
    }

    @Test
    fun `picks the emptiest hour among several clear ones`() {
        // Both clear; the curve dips low only under x=340 (more clearance), high under x=260.
        val curve: (Float) -> Float? = { x -> if (x >= 320f) 195f else 90f }
        val nearLabel = GhostLineLabel.Candidate(x = 260f, ghostY = 60f, expectedTemp = 60f, hasHourLabel = true)
        val farLabel = GhostLineLabel.Candidate(x = 340f, ghostY = 60f, expectedTemp = 70f, hasHourLabel = true)
        val p = place(listOf(nearLabel, farLabel), curveYAt = curve)
        assertNotNull(p)
        assertEquals(340f, p!!.centerX, 0.001f) // the hour with the curve farther away wins
    }
}
