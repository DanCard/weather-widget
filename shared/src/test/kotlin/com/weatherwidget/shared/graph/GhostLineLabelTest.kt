package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class GhostLineLabelTest {

    // A flat curve sitting near the bottom of the plot, leaving the top band empty.
    private val lowCurve: (Float) -> Float? = { 180f }
    private val metrics = GhostLineLabel.Metrics(width = 40f, ascent = -10f, descent = 4f) // height 14
    private val plot = GraphRect(0f, 0f, 400f, 200f)

    /** A clear hour point near the top of the plot (well above the low curve), in the right half. */
    private fun rightCandidate(x: Float, temp: Float = 69.4f, hasHourLabel: Boolean = true) =
        GhostLineLabel.Candidate(x = x, ghostY = 60f, expectedTemp = temp, hasHourLabel = hasHourLabel)

    private fun placeAll(
        candidates: List<GhostLineLabel.Candidate>,
        spanHours: Long = 6,
        drawnBounds: List<GraphRect> = emptyList(),
        curveYAt: (Float) -> Float? = lowCurve,
    ) = GhostLineLabel.placeAll(
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
    fun `format returns placeholder for non-finite temperature`() {
        assertEquals("--°", GhostLineLabel.format(Float.NaN))
        assertEquals("--°", GhostLineLabel.format(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `placeAll formats non-finite candidate as placeholder without crashing`() {
        val placements = placeAll(listOf(rightCandidate(300f, temp = Float.NaN)))
        assertEquals(1, placements.size)
        assertEquals("--°", placements[0].text)
    }

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
        val p = placeAll(listOf(rightCandidate(300f)))
        assertEquals(1, p.size)
        assertEquals("69.4°", p[0].text)
        // Sits above the ghost line (y=60) by the gap, clear of the low curve.
        assertTrue(p[0].box.bottom <= 60f)
        assertTrue(p[0].box.bottom < 180f)
        assertEquals(p[0].box.top + 10f, p[0].baselineY, 0.001f) // baseline below top by |ascent|
    }

    @Test
    fun `labels every clear right-half hour, not just one`() {
        // Two well-separated clear hours: both get a label.
        val p = placeAll(listOf(rightCandidate(260f, temp = 60f), rightCandidate(340f, temp = 70f)))
        assertEquals(2, p.size)
        assertEquals(setOf(260f, 340f), p.map { it.centerX }.toSet())
        assertEquals(setOf("60.0°", "70.0°"), p.map { it.text }.toSet())
    }

    @Test
    fun `skips an hour whose only open spot would overlap an earlier ghost label`() {
        // Two hours close enough in x that their boxes overlap; the curve sits just below the line so
        // the "below" spot is blocked. The first hour takes the spot above the line; the second has
        // nowhere left (above overlaps the first label, below hits the curve) and is skipped.
        val tightCurve: (Float) -> Float? = { 75f }
        val p = placeAll(
            listOf(rightCandidate(300f, temp = 60f), rightCandidate(315f, temp = 61f)),
            curveYAt = tightCurve,
        )
        assertEquals(1, p.size)
        assertEquals(300f, p[0].centerX, 0.001f)
    }

    @Test
    fun `suppressed past the narrow-view span max`() {
        assertTrue(placeAll(listOf(rightCandidate(300f)), spanHours = GhostLineLabel.MAX_HOURS_SPAN).isNotEmpty())
        assertTrue(placeAll(listOf(rightCandidate(300f)), spanHours = GhostLineLabel.MAX_HOURS_SPAN + 1).isEmpty())
    }

    @Test
    fun `ignores left-half hours, only labels the right half`() {
        // Only a left-half candidate (x below the 200 midpoint): nothing to place.
        assertTrue(placeAll(listOf(rightCandidate(80f))).isEmpty())
        // A right-half candidate is placed at its x.
        val p = placeAll(listOf(rightCandidate(300f)))
        assertEquals(1, p.size)
        assertEquals(300f, p[0].centerX, 0.001f)
    }

    @Test
    fun `only labels footer-labeled hours when some carry a footer label`() {
        val unlabeled = rightCandidate(260f, temp = 60f, hasHourLabel = false)
        val labeled = rightCandidate(340f, temp = 70f, hasHourLabel = true)
        val p = placeAll(listOf(unlabeled, labeled))
        assertEquals(1, p.size)
        assertEquals(340f, p[0].centerX, 0.001f)
        assertEquals("70.0°", p[0].text)
    }

    @Test
    fun `falls back to unlabeled hours when none carry a footer label`() {
        val a = rightCandidate(260f, temp = 60f, hasHourLabel = false)
        val b = rightCandidate(340f, temp = 70f, hasHourLabel = false)
        val p = placeAll(listOf(a, b))
        assertEquals(2, p.size)
        assertEquals(setOf(260f, 340f), p.map { it.centerX }.toSet())
    }

    @Test
    fun `empty when every right-half hour collides with an existing label`() {
        val blocker = GraphRect(200f, 0f, 400f, 200f) // covers the entire right half
        assertTrue(placeAll(listOf(rightCandidate(260f), rightCandidate(340f)), drawnBounds = listOf(blocker)).isEmpty())
    }

    @Test
    fun `places the emptiest spot for each hour`() {
        // The curve dips low only under x=340 (more clearance), high under x=260; both still clear.
        val curve: (Float) -> Float? = { x -> if (x >= 320f) 195f else 90f }
        val p = placeAll(listOf(rightCandidate(260f, temp = 60f), rightCandidate(340f, temp = 70f)), curveYAt = curve)
        assertEquals(2, p.size)
        val far = p.first { it.centerX == 340f }
        assertNotNull(far)
    }
}
