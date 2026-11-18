package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Fold 4, 2026-11-18: "Act .4in" was drawn on the rain-chance line. The placer took the first y
 * free of other labels and never looked at the curve. See
 * plans/261118-rain-amount-labels-avoid-curve.md.
 */
@Category(ShortDuration::class)
class RainAmountLabelPlacerCurveTest {

    private val graphTop = 0f
    private val graphBottom = 200f

    private fun place(curve: List<Pair<Float, Float>>, collisions: List<GraphRect> = emptyList()) =
        RainAmountLabelPlacer.calculatePlacements(
            rainPeriods = listOf(
                RainPeriodSelection.RainPeriod(startIndex = 0, endIndex = 0, totalAmountMm = 10f, anchorX = 200f),
            ),
            widthPx = 400f,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphBottom - graphTop,
            initialCollisionBounds = collisions,
            curvePoints = curve,
            labelPrefix = "Act ",
            observed = true,
            measureText = { it.length * 6f },
            getTextBounds = { -5f to 5f },
            paddingPx = 2f,
        ).single()

    @Test
    fun `a label is not placed where the curve passes through it`() {
        // Plateau at y=24 = the first candidate row (0.12 x 200). The old placer accepted it.
        val plateau = listOf(0f to 150f, 120f to 24f, 280f to 24f, 400f to 150f)
        val p = place(plateau)
        println("plateau placement y=${p.y} bounds=${p.bounds}")
        assertFalse(
            "Label ${p.bounds} must not be crossed by the curve",
            RainAmountLabelPlacer.curveCrosses(p.bounds.inflate(2f), plateau),
        )
        assertTrue("Moved to a lower row, below the line", p.bounds.top > 24f)
    }

    @Test
    fun `a steep segment between samples still counts as crossing`() {
        // Vertical drop at x=200 from above the box to below it: no sample lands inside the box.
        val cliff = listOf(0f to 0f, 200f to 0f, 200.01f to 200f, 400f to 200f)
        assertTrue(RainAmountLabelPlacer.curveCrosses(GraphRect(190f, 90f, 210f, 110f), cliff))
    }

    @Test
    fun `with no clear row the fallback prefers a row the curve does not cross`() {
        // Every row collides with an equal-sized label strip at the anchor; only the curve decides.
        val strips = HourlyGraphDefaults.OVERLAY_Y_FRACTIONS.map { f ->
            val cy = graphBottom * f
            GraphRect(150f, cy - 1f, 250f, cy + 1f)
        }
        val plateau = listOf(0f to 24f, 400f to 24f)
        val p = place(plateau, strips)
        println("fallback placement y=${p.y} overlap=${p.overlapArea}")
        assertFalse(
            "Fallback must not pick the row the curve crosses",
            RainAmountLabelPlacer.curveCrosses(p.bounds.inflate(2f), plateau),
        )
    }

    @Test
    fun `when every row crosses a steep climb the label still never lands on another label`() {
        // Fold 4 left edge, 2026-11-18: 25% at 3a climbing to 85% by 7a crosses every row at the
        // night anchor. A crossing priced as a label's area made "on top of Pred" the cheapest
        // spot, and "Act .099in" was drawn over "Pred .25in".
        val climb = listOf(0f to 200f, 150f to 200f, 250f to 0f, 400f to 0f)
        val pred = GraphRect(170f, 19f, 230f, 29f) // "Pred" already in the top row at the anchor
        val p = place(climb, listOf(pred))
        println("steep-climb placement y=${p.y} bounds=${p.bounds} overlap=${p.overlapArea}")
        assertFalse("Act ${p.bounds} must not overlap Pred $pred", p.bounds.intersects(pred))
    }
}
