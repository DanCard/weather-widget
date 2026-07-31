package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DualHighLabelTest {

    private val labelH = 20f

    @Test
    fun `null inputs never show both`() {
        assertFalse(DualHighLabel.showBoth(null, 70f, 0f, 40f, labelH))
        assertFalse(DualHighLabel.showBoth(70f, null, 0f, 40f, labelH))
    }

    // Min gap (px) the room test requires, given labelH.
    private val minGap = labelH * (1f - DualHighLabel.MAX_OVERLAP_FRACTION)

    @Test
    fun `difference below floor does not show both even with room`() {
        // Just under the floor but plenty of vertical room -> still suppressed by the floor.
        // Expressed as a fraction of the constant (not `- 1f`) so the floor can be tuned without
        // the "below" case silently going negative and asserting the opposite of its name.
        val belowFloor = DualHighLabel.MIN_DIFF_DEG * 0.5f
        assertFalse(DualHighLabel.showBoth(72f, 72f - belowFloor, 0f, 100f, labelH))
    }

    @Test
    fun `a one-degree miss on a real graph shows both - the room test is the gate, not the floor`() {
        // Regression for the Samsung history report (2026-07-31): Mon showed only the actual high
        // while Sun/Wed/Thu showed both, purely because its miss (75.9 actual vs 77.0 forecast =
        // 1.08°) fell under the old 2° floor -- with obvious empty space above the label.
        //
        // Reproduces that column's real geometry rather than abstract Y values: the fold renders
        // ~20px/° at density 3.03, and the planner feeds showBoth the baselines AFTER
        // bottomOffsetsDp has moved the two labels apart (DailyHighLabelPlanner.resolveHighLabelPlan).
        val pxPerDeg = 20f
        val density = 3.03f
        val fullLabelH = 60f // measured "75.9°" box on the device
        val actualHigh = 75.9f
        val forecastHigh = 77.0f

        fun tempToY(t: Float) = -t * pxPerDeg // warmer temp -> smaller Y, as on both renderers
        val offsets = DualHighLabel.bottomOffsetsDp(actualHigh, forecastHigh, normalGapDp = 8f)
        val actualBaseline = tempToY(actualHigh) + offsets.actualDp * density
        val forecastBaseline = tempToY(forecastHigh) + offsets.forecastDp * density
        val twoLabelH = fullLabelH * DualHighLabel.TWO_LABEL_FONT_SCALE

        assertTrue(
            "a 1.08° miss with ~37px of label separation has room for both labels",
            DualHighLabel.showBoth(actualHigh, forecastHigh, actualBaseline, forecastBaseline, twoLabelH),
        )
        // Falsifies the fix: this is the assertion that fails at the old 2° floor.
        assertTrue(
            "the floor must not pre-empt the room test at sub-2° misses",
            DualHighLabel.MIN_DIFF_DEG <= kotlin.math.abs(actualHigh - forecastHigh),
        )
    }

    @Test
    fun `substantial difference with room shows both`() {
        // Comfortably over the floor and the two label boxes are well past the min gap.
        assertTrue(DualHighLabel.showBoth(80f, 72f, 0f, minGap + 20f, labelH))
    }

    @Test
    fun `substantial difference but boxes overlap too much does not show both`() {
        // Over the floor by value, but on a very compressed graph the labels are below the min gap.
        assertFalse(DualHighLabel.showBoth(80f, 72f, 0f, minGap - 4f, labelH))
    }

    @Test
    fun `gap exactly at the overlap boundary shows both`() {
        // gap == labelH * (1 - MAX_OVERLAP_FRACTION) -> inclusive.
        assertTrue(DualHighLabel.showBoth(80f, 72f, 0f, minGap, labelH))
    }

    // ── bottomOffsetsDp ──────────────────────────────────────────────────
    // Offsets are each label's BOTTOM edge relative to its own bar top; positive = down the screen.

    private val gap = 8f

    @Test
    fun `lower-valued label sits just below its bar top, higher-valued clears its normal gap`() {
        // Actual warmer than forecast (the reported case: pink 89.4 over yellow 87).
        val o = DualHighLabel.bottomOffsetsDp(actualHigh = 89.4f, forecastHigh = 87f, normalGapDp = gap)
        assertEquals(DualHighLabel.DUAL_LOWER_BELOW_BAR_DP, o.forecastDp, 0.001f)
        assertEquals(-(gap + DualHighLabel.DUAL_UPPER_PUSH_UP_DP), o.actualDp, 0.001f)
    }

    @Test
    fun `roles swap when the forecast ran hot`() {
        // Forecast above the actual -> the ACTUAL is now the lower label and takes the below-bar spot.
        val o = DualHighLabel.bottomOffsetsDp(actualHigh = 87f, forecastHigh = 89.4f, normalGapDp = gap)
        assertEquals(DualHighLabel.DUAL_LOWER_BELOW_BAR_DP, o.actualDp, 0.001f)
        assertEquals(-(gap + DualHighLabel.DUAL_UPPER_PUSH_UP_DP), o.forecastDp, 0.001f)
    }

    @Test
    fun `labels always move apart, never together, whichever high is larger`() {
        // The whole point of the direction-aware rule: a role-based "always lower the forecast" could
        // shove the two together (and cross them) when the forecast ran hot. Assert separation grows
        // in both orientations, on a graph compressed enough that a minimum-diff miss barely shows.
        val pxPerDeg = 14f
        fun separationChange(actual: Float, forecast: Float): Float {
            val o = DualHighLabel.bottomOffsetsDp(actual, forecast, normalGapDp = gap)
            val naturalGap = kotlin.math.abs(actual - forecast) * pxPerDeg
            // Bar tops in screen Y: warmer temp = smaller Y. Apply each offset to its own bar top.
            val aY = -actual * pxPerDeg + o.actualDp
            val fY = -forecast * pxPerDeg + o.forecastDp
            return kotlin.math.abs(aY - fY) - naturalGap
        }
        assertTrue(separationChange(89.4f, 87f) > 0f)
        assertTrue(separationChange(87f, 89.4f) > 0f)
    }

    @Test
    fun `order never flips even at the minimum labelable difference`() {
        // MIN_DIFF_DEG on a compressed graph is the tightest case the gate ever admits; the warmer
        // label must still end up above the cooler one (smaller Y), or the numbers would contradict
        // the bars they sit on. BOTH orientations matter — the flip a role-based nudge causes only
        // shows up when the FORECAST ran hot, so testing actual-warmer alone would pass vacuously.
        val pxPerDeg = 1f // absurdly compressed: the nudges dwarf the temperature difference
        fun warmerLabelY(actual: Float, forecast: Float): Pair<Float, Float> {
            val o = DualHighLabel.bottomOffsetsDp(actual, forecast, normalGapDp = gap)
            return (-actual * pxPerDeg + o.actualDp) to (-forecast * pxPerDeg + o.forecastDp)
        }
        val (aWarmA, aWarmF) = warmerLabelY(89f, 89f - DualHighLabel.MIN_DIFF_DEG)
        assertTrue("warmer actual must stay above the cooler forecast", aWarmA < aWarmF)

        val (fWarmA, fWarmF) = warmerLabelY(87f, 87f + DualHighLabel.MIN_DIFF_DEG)
        assertTrue("warmer forecast must stay above the cooler actual", fWarmF < fWarmA)
    }

    @Test
    fun `equal highs do not crash and keep the actual above`() {
        // Unreachable through showBoth (MIN_DIFF_DEG gates it), but the pure fn must stay total.
        val o = DualHighLabel.bottomOffsetsDp(80f, 80f, normalGapDp = gap)
        assertTrue(o.actualDp < o.forecastDp)
    }

    // ── extraUpperPushPx (on-demand raise for the upper label) ───────────

    // The separation the push aims for, given labelH.
    private val targetGap = labelH * DualHighLabel.DUAL_TARGET_SEPARATION_FRACTION

    @Test
    fun `a pair already at the target separation gets no extra push`() {
        assertEquals(0f, DualHighLabel.extraUpperPushPx(targetGap, labelH, 100f), 0.001f)
        assertEquals(0f, DualHighLabel.extraUpperPushPx(targetGap + 50f, labelH, 100f), 0.001f)
    }

    @Test
    fun `extra push covers exactly the shortfall against the target separation`() {
        val shortfall = 3f
        assertEquals(shortfall, DualHighLabel.extraUpperPushPx(targetGap - shortfall, labelH, 100f), 0.001f)
    }

    @Test
    fun `the push aims past the admission tolerance, not at it`() {
        // The bug this encodes: aiming at MAX_OVERLAP_FRACTION parks the labels AT the maximum
        // tolerated overlap, which prints digits on top of each other ("I don't like the overlap
        // ... there is lots of room above"). Admission is a tolerance; placement is a target.
        assertTrue(
            "target separation must exceed the admission threshold",
            DualHighLabel.DUAL_TARGET_SEPARATION_FRACTION > (1f - DualHighLabel.MAX_OVERLAP_FRACTION),
        )
        // Cap height is ~0.61 of a measured box for the default font; clearing the visible digits
        // is the whole point of the target, so it must sit above that with air to spare.
        assertTrue(
            "target separation must clear the visible digits",
            DualHighLabel.DUAL_TARGET_SEPARATION_FRACTION > 0.61f,
        )
    }

    @Test
    fun `extra push is capped, and a pair still too tight stays rejected`() {
        val cap = 2f
        assertEquals(cap, DualHighLabel.extraUpperPushPx(0f, labelH, cap), 0.001f)
        // Capped push isn't enough here, so showBoth must still say no -- the cap is not a bypass.
        assertFalse(DualHighLabel.showBoth(80f, 72f, 0f, 0f + cap, labelH))
    }

    @Test
    fun `pushing the upper label clears a borderline pair instead of dropping or overlapping it`() {
        // The device case (2026-07-31 Samsung history, Mon): actual 75.9 / forecast 77.0 at
        // 7.75px per degree in bitmap space landed 13.53px apart against a 13.84px admission
        // threshold -- rejected by 0.31px, with empty space directly above the upper (forecast)
        // label. Aiming only at that threshold would have printed them overlapping instead.
        val pxPerDeg = 7.75f
        val deviceLabelH = 34.59f
        val fixedNudgePx = 5.16f // bottomOffsetsDp's -5dp vs -10dp, at this device's density
        val gapBefore = 1.08f * pxPerDeg + fixedNudgePx

        assertFalse(
            "precondition: the pre-push positions really are short of the admission test",
            DualHighLabel.showBoth(75.9f, 77.0f, 0f, gapBefore, deviceLabelH),
        )
        val push = DualHighLabel.extraUpperPushPx(
            gapBefore,
            deviceLabelH,
            maxExtraPushPx = deviceLabelH * DualHighLabel.DUAL_UPPER_MAX_EXTRA_PUSH_FRACTION,
        )
        val gapAfter = gapBefore + push
        assertTrue(
            "after the push the pair is admitted",
            DualHighLabel.showBoth(75.9f, 77.0f, 0f, gapAfter, deviceLabelH),
        )
        assertTrue(
            "and the digits actually clear each other (cap height ~0.61 of the box)",
            gapAfter >= deviceLabelH * 0.61f,
        )
    }

    // ── forecastFontScale (collision-gated shrink) ───────────────────────
    // The forecast only gives up size when the two boxes genuinely collide at full size; a
    // well-separated forecast keeps the normal dual-label font (user request: shrink on
    // collision ONLY, never as a permanent style). Because a label box is taller than its
    // visible digits, a small box overlap doesn't count as a collision.

    // Same-edge gap (px) below which the shrink kicks in, given labelH.
    private val collisionGap = labelH * (1f - DualHighLabel.FONT_SHRINK_ALLOWED_OVERLAP_FRACTION)

    @Test
    fun `forecast keeps full size when the labels are clear of each other`() {
        assertEquals(1f, DualHighLabel.forecastFontScale(0f, labelH, labelH), 0.001f)
        assertEquals(1f, DualHighLabel.forecastFontScale(0f, labelH + 30f, labelH), 0.001f)
    }

    @Test
    fun `small box overlap is tolerated at full size`() {
        // Box overlap up to the allowed fraction still reads as separated on screen (the visible
        // digits are shorter than the measured box) -> no shrink. Boundary is inclusive.
        assertEquals(1f, DualHighLabel.forecastFontScale(0f, collisionGap, labelH), 0.001f)
        assertEquals(1f, DualHighLabel.forecastFontScale(0f, collisionGap + 1f, labelH), 0.001f)
    }

    @Test
    fun `lower forecast shrinks when the full-size boxes genuinely collide`() {
        // Forecast below the actual (larger Y), gap under the tolerance threshold -> shrink.
        assertEquals(DualHighLabel.DUAL_FORECAST_FONT_SCALE,
            DualHighLabel.forecastFontScale(0f, collisionGap - 1f, labelH), 0.001f)
    }

    @Test
    fun `forecast on top never shrinks, even when colliding`() {
        // A bottom-pinned label's bottom edge doesn't move when shrunk, so shrinking an UPPER
        // forecast can't open the gap to the actual below it — and there's open space above.
        assertEquals(1f, DualHighLabel.forecastFontScale(collisionGap - 1f, 0f, labelH), 0.001f)
        assertEquals(1f, DualHighLabel.forecastFontScale(labelH, 1f, labelH), 0.001f)
    }

    @Test
    fun `colliding forecast label draws smaller than the actual even when the actual is the wide one`() {
        // In the collision case, "89.4°" trips isWideLabel (-5%) while "87°" does not — without the
        // role-keyed shrink the forecast would draw BIGGER than the headline actual on digit count.
        val base = 100f
        val forecastScale = DualHighLabel.forecastFontScale(0f, 4f, labelH) // colliding
        val actualSize = base * DualHighLabel.TWO_LABEL_FONT_SCALE *
            (if (DualHighLabel.isWideLabel("89.4°")) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)
        val forecastSize = base * DualHighLabel.TWO_LABEL_FONT_SCALE * forecastScale *
            (if (DualHighLabel.isWideLabel("87°")) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)
        assertTrue("colliding forecast must read as the secondary number", forecastSize < actualSize)
    }

    @Test
    fun `isWideLabel true for 3-plus digit temps`() {
        assertTrue(DualHighLabel.isWideLabel("100°"))   // triple-digit int
        assertTrue(DualHighLabel.isWideLabel("97.7°"))  // decimal -> 3 digits
        assertFalse(DualHighLabel.isWideLabel("84°"))   // 2 digits
        assertFalse(DualHighLabel.isWideLabel("9°"))    // 1 digit
    }
}
