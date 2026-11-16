package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.DailyRainLabelPlanner.Box
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import kotlin.math.abs
import kotlin.random.Random

/**
 * Outcome tests for the scored night rain label placement
 * (plans/261116-night-rain-label-scored-placement-redesign.md): ink clear of every obstacle, centred
 * between the columns, not dropped further than it needs. Fixtures are the Fold 4 NWS traces of
 * 2026-11-16 (Tue→Wed ".51in", Wed→Thu "94%", Thu→Fri "33%"). The logs recorded line boxes; the ink
 * here is derived from them the way the Fold's glyphs sit in those boxes (digits: blank top 20 %,
 * bottom 22 %, ~5 px left side bearing; °: top half), which the hosts now measure directly.
 */
@Category(ShortDuration::class)
class NightRainLabelPlacerTest {
    private val p = 2.625f

    /** Android digit boxes carry 5–7 canvas px of side bearing before the first glyph's ink. */
    private val DIGIT_SIDE_PAD = 5f

    /** A low label's line boxes → its ink: digits and ° as separate boxes. */
    private fun lowInk(digits: Box, degree: Box): List<Box> {
        val h = digits.bottom - digits.top
        return listOf(
            Box(digits.left + DIGIT_SIDE_PAD, digits.top + h * 0.2f, digits.right - 1f, digits.bottom - h * 0.22f),
            Box(degree.left, degree.top + h * 0.2f, degree.right - 2f, degree.top + h * 0.45f),
        )
    }

    private fun union(boxes: List<Box>) =
        Box(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })

    /** A label host with fixed size; ink from 20 % down the box to the baseline, 1 px in from each side. */
    private fun host(width: Float, height: Float, baseline: Float) = object : DailyRainLabelLayout.Host {
        override fun measure(text: String, kind: DailyRainLabelLayout.Kind, fontScale: Float) =
            DailyRainLabelLayout.TextSize(width * fontScale / baseScale(text), height * fontScale / baseScale(text), baseline * fontScale / baseScale(text))

        override fun ink(text: String, kind: DailyRainLabelLayout.Kind, fontScale: Float): Box {
            val s = measure(text, kind, fontScale)
            return Box(1f, s.height * 0.2f, s.width - 1f, s.baseline)
        }

        private val scales = mutableMapOf<String, Float>()

        // The first scale asked for each text is its full size; the logged sizes are at that scale.
        fun baseScale(text: String) = scales[text] ?: 1f

        fun prime(input: DailyRainLabelLayout.NightInput) {
            scales[input.text] = com.weatherwidget.shared.util.DailyRainLabels.rainLabelFontScale(input.isPast, input.probability, input.daysFromToday) *
                com.weatherwidget.shared.util.DailyRainLabels.NIGHT_SCALE *
                com.weatherwidget.shared.util.DailyRainLabels.traceFontScale(input.text)
        }
    }

    private class Case(val input: DailyRainLabelLayout.NightInput, val width: Float, val height: Float, val baseline: Float)

    private fun case(
        text: String,
        probability: Int?,
        width: Float,
        height: Float,
        gapCenterX: Float,
        leftIcon: Box,
        leftDigits: Box,
        leftDegree: Box,
        rightIcon: Box,
        rightDigits: Box,
        rightDegree: Box,
    ): Case {
        val left = lowInk(leftDigits, leftDegree)
        val right = lowInk(rightDigits, rightDegree)
        return Case(
            DailyRainLabelLayout.NightInput(
                date = "2026-11-16", text = text, probability = probability, isPast = false, daysFromToday = 2,
                columnCenterX = gapCenterX - 27.55f, gapCenterX = gapCenterX, columnWidth = 55.1f,
                leftLowInk = union(left), rightLowInk = union(right),
                // Day names: about 28 px wide, centred on each column, ink from the floor down.
                obstacles = listOf(leftIcon) + left + listOf(rightIcon) + right +
                    listOf(gapCenterX - 27.55f, gapCenterX + 27.55f).map { Box(it - 14f, 378f, it + 14f, 390f) },
                floorInkTop = 378f, canvasWidth = 560f, pxPerDp = p,
            ),
            width, height, height * 0.78f,
        )
    }

    private val tueWed51in = case(
        ".51in", 94, 37f, 19.9f, 234.3f,
        Box(191.2f, 303.3f, 222.2f, 334.3f), Box(183.7f, 334.0f, 217.7f, 370.9f), Box(217.7f, 334.0f, 229.7f, 352.5f),
        Box(246.3f, 242.4f, 277.3f, 273.4f), Box(238.8f, 273.1f, 272.8f, 310.0f), Box(272.8f, 273.1f, 284.8f, 291.5f),
    )
    private val wedThu94 = case(
        "94%", 94, 30f, 19.7f, 289.4f,
        Box(246.3f, 242.4f, 277.3f, 273.4f), Box(238.8f, 273.1f, 272.8f, 310.0f), Box(272.8f, 273.1f, 284.8f, 291.5f),
        Box(301.5f, 294.6f, 332.5f, 325.6f), Box(294.0f, 325.3f, 328.0f, 362.2f), Box(328.0f, 325.3f, 340.0f, 343.7f),
    )
    private val thuFri33 = case(
        "33%", 33, 23f, 14.8f, 344.5f,
        Box(301.5f, 294.6f, 332.5f, 325.6f), Box(294.0f, 325.3f, 328.0f, 362.2f), Box(328.0f, 325.3f, 340.0f, 343.7f),
        Box(356.6f, 312.0f, 387.6f, 343.0f), Box(349.1f, 342.7f, 383.1f, 379.6f), Box(383.1f, 342.7f, 395.1f, 361.2f),
    )

    private fun layout(vararg cases: Case): List<Pair<DailyRainLabelLayout.Placed, Box>> {
        val c = cases.first()
        val h = host(c.width, c.height, c.baseline)
        cases.forEach { h.prime(it.input) }
        // One host per text size: the fixtures differ in size, so lay each out with its own host.
        val placed = if (cases.size == 1) {
            DailyRainLabelLayout.layoutNights(h, listOf(c.input), "test")
        } else {
            val multi = object : DailyRainLabelLayout.Host {
                val hosts = cases.associate { it.input.text to host(it.width, it.height, it.baseline).also { x -> x.prime(it.input) } }
                override fun measure(text: String, kind: DailyRainLabelLayout.Kind, fontScale: Float) = hosts.getValue(text).measure(text, kind, fontScale)
                override fun ink(text: String, kind: DailyRainLabelLayout.Kind, fontScale: Float) = hosts.getValue(text).ink(text, kind, fontScale)
            }
            DailyRainLabelLayout.layoutNights(multi, cases.map { it.input }, "test")
        }
        return placed.map { label ->
            val s = DailyRainLabelLayout.TextSize(label.width, label.height, label.baseline - label.top)
            label to Box(label.left + 1f, label.top + s.height * 0.2f, label.right - 1f, label.baseline)
        }
    }

    private fun assertClear(name: String, ink: Box, obstacles: List<Box>) {
        val gap = obstacles.minOf { NightRainLabelPlacer.gap(ink, it) }
        println("$name ink=$ink minGapDp=${gap / p}")
        assertTrue("$name ink at least 1 dp from every obstacle: ${gap / p} dp", gap >= p - 0.01f)
    }

    private fun offCentreDp(ink: Box, target: Float) = ((ink.left + ink.right) / 2f - target) / p

    @Test
    fun `fold 94 percent drops to sit centred between Wed and Thu`() {
        // Old placement: 5 dp left of centre (inkSlideDp=-4.0) under Wed's column, at top 316.6. User
        // approved centre-first (2026-11-16): on the row under Wed's low Thu's cloud blocks the centre,
        // so it drops to the clear centred spot above the day names.
        val (label, ink) = layout(wedThu94).single()
        println("94%: centre=${label.centerX} top=${label.top} offCentreDp=${offCentreDp(ink, 289.4f)}")
        assertClear("94%", ink, wedThu94.input.obstacles)
        assertTrue("within 1 dp of centre", abs(offCentreDp(ink, 289.4f)) <= 1f)
        assertTrue("ink at most 1.5 dp past the day names' top", ink.bottom <= 378f + 1.5f * p + 0.01f)
    }

    @Test
    fun `fold 33 percent is centred between Thu and Fri and stays above the day names`() {
        val (label, ink) = layout(thuFri33).single()
        println("33%: centre=${label.centerX} top=${label.top} offCentreDp=${offCentreDp(ink, 344.5f)}")
        assertClear("33%", ink, thuFri33.input.obstacles)
        // assertClear covers the day names' ink; between the names it may pass their top by 1.5 dp.
        assertTrue("ink at most 1.5 dp past the day names' top", ink.bottom <= 378f + 1.5f * p + 0.01f)
        // Old placement: 1 dp left, level with Fri's "42" (read "33%42°"). These fixture boxes are
        // solid, so the 4's open corner is closed here; on the Fold, with 1 px row bands for the 4 and
        // the label, it lands 0.7 dp off centre (2026-11-16 11:5x trace).
        assertTrue("within 2 dp of centre", abs(offCentreDp(ink, 344.5f)) <= 2f)
    }

    @Test
    fun `fold 51in drops below Tue's icon to sit centred between Tue and Wed`() {
        val (label, ink) = layout(tueWed51in).single()
        println(".51in: centre=${label.centerX} top=${label.top} offCentreDp=${offCentreDp(ink, 234.3f)}")
        assertClear(".51in", ink, tueWed51in.input.obstacles)
        // Old placement: 3 dp right (centre 242.1) beside Tue's sun icon, at top 316.6.
        assertTrue("within 1.5 dp of centre", abs(offCentreDp(ink, 234.3f)) <= 1.5f)
    }

    @Test
    fun `neighbouring night labels never overlap each other`() {
        val placed = layout(tueWed51in, wedThu94, thuFri33)
        placed.forEachIndexed { i, (label, ink) -> println("${label.text}: centre=${label.centerX} top=${label.top} ink=$ink") }
        for (i in 1 until placed.size) {
            val gap = NightRainLabelPlacer.gap(placed[i - 1].second, placed[i].second)
            assertTrue("${placed[i - 1].first.text} and ${placed[i].first.text} at least 1 dp apart: ${gap / p}", gap >= p - 0.01f)
        }
    }

    @Test
    fun `a label centres into the open corner under a 4's crossbar that a bounding box would block`() {
        // Fold Thu->Fri "33%" beside Fri's "42" (2026-11-16): the 4 is open below its crossbar.
        val crossbar = Box(105f, 40f, 125f, 50f)
        val stem = Box(118f, 50f, 122f, 62f)
        fun place(obstacles: List<Box>) = NightRainLabelPlacer.place(
            width = 20f, ink = Box(0f, 0f, 20f, 8f), targetCenterX = 100f, preferredTop = 52f, minTop = 44f,
            maxInkBottom = 62f, minLeft = 0f, maxRight = 300f, maxShift = 15f, obstacles = obstacles, density = 1f,
        ).best
        val banded = place(listOf(crossbar, stem))
        val boxed = place(listOf(Box(105f, 40f, 125f, 62f)))
        println("bands: centre=${banded.centerX} top=${banded.top}; one box: centre=${boxed.centerX} top=${boxed.top}")
        assertTrue("bands: centred under the crossbar, beside the stem", abs(banded.centerX - 100f) <= 0.5f && banded.clear)
        assertTrue("one box: pushed left", boxed.centerX < 96f)
    }

    @Test
    fun `a corner-to-corner gap needs only the corner clearance`() {
        // Fold "33%": its bottom-left corner diagonal to the top-right corner of "Thu".
        val w = NightRainLabelPlacer.DEFAULT_WEIGHTS
        val label = Box(0f, 0f, 20f, 10f)
        val diagonal = Box(20f + 0.5f, 10f + 0.6f, 40f, 30f) // ~0.78 px apart, corner to corner
        val beside = Box(20.78f, 0f, 40f, 10f)              // same distance, side by side
        assertTrue(NightRainLabelPlacer.isCorner(label, diagonal))
        assertFalse(NightRainLabelPlacer.isCorner(label, beside))
        val p = 1.3f
        assertTrue("diagonal 0.78 px >= ${w.cornerClearanceDp} dp", NightRainLabelPlacer.gap(label, diagonal) / p >= w.cornerClearanceDp)
        assertTrue("beside 0.78 px < ${w.clearanceDp} dp", NightRainLabelPlacer.gap(label, beside) / p < w.clearanceDp)
    }

    @Test
    fun `label bands let its own open corner pass close to another glyph`() {
        // A label whose bottom-left corner is blank (the curve of a "3"): as one box it collides.
        val obstacle = Box(80f, 58f, 89.5f, 70f)
        val bands = listOf(Box(0f, 0f, 20f, 4f), Box(2f, 4f, 20f, 8f))
        fun place(inkBands: List<Box>) = NightRainLabelPlacer.place(
            width = 20f, ink = Box(0f, 0f, 20f, 8f), inkBands = inkBands, targetCenterX = 100f, preferredTop = 50f,
            minTop = 50f, maxInkBottom = 58f, minLeft = 0f, maxRight = 300f, maxShift = 0f, obstacles = listOf(obstacle), density = 1f,
        ).best
        assertFalse("one box: the corner touches", place(listOf(Box(0f, 0f, 20f, 8f))).clear)
        assertTrue("bands: the blank corner clears", place(bands).clear)
    }

    @Test
    fun `a label with nothing in the way sits at the target on the preferred row`() {
        val result = NightRainLabelPlacer.place(
            width = 20f, ink = Box(1f, 3f, 19f, 11f), targetCenterX = 100f, preferredTop = 50f, minTop = 40f,
            maxInkBottom = 200f, minLeft = 0f, maxRight = 300f, maxShift = 15f, obstacles = emptyList(), density = 1f,
        )
        assertEquals(100f, result.best.centerX, 0.01f)
        assertEquals(50f, result.best.top, 0.01f)
        assertTrue(result.best.clear)
    }

    @Test
    fun `with no clear spot it takes the least intrusive one and says so`() {
        val wall = Box(0f, 0f, 300f, 300f)
        val result = NightRainLabelPlacer.place(
            width = 20f, ink = Box(1f, 3f, 19f, 11f), targetCenterX = 100f, preferredTop = 50f, minTop = 40f,
            maxInkBottom = 200f, minLeft = 0f, maxRight = 300f, maxShift = 15f, obstacles = listOf(wall), density = 1f,
        )
        assertFalse(result.best.clear)
    }

    @Test
    fun `gap is the distance between boxes, negative by the shallower penetration`() {
        assertEquals(5f, NightRainLabelPlacer.gap(Box(0f, 0f, 10f, 10f), Box(15f, 0f, 20f, 10f)), 0.001f)
        assertEquals(5f, NightRainLabelPlacer.gap(Box(0f, 0f, 10f, 10f), Box(13f, 14f, 20f, 20f)), 0.001f)
        assertEquals(-2f, NightRainLabelPlacer.gap(Box(0f, 0f, 10f, 10f), Box(8f, 0f, 20f, 10f)), 0.001f)
    }

    @Test
    fun `whenever a clear spot exists the chosen spot is clear`() {
        val rnd = Random(7)
        repeat(300) { n ->
            val obstacles = List(rnd.nextInt(1, 7)) {
                val x = rnd.nextFloat() * 200f
                val y = rnd.nextFloat() * 100f
                Box(x, y, x + 5f + rnd.nextFloat() * 30f, y + 5f + rnd.nextFloat() * 30f)
            }
            val args = { w: NightRainLabelPlacer.Weights ->
                NightRainLabelPlacer.place(
                    width = 20f, ink = Box(1f, 3f, 19f, 11f), targetCenterX = 100f, preferredTop = 30f, minTop = 20f,
                    maxInkBottom = 120f, minLeft = 0f, maxRight = 200f, maxShift = 20f, obstacles = obstacles, density = 1f, weights = w,
                )
            }
            val best = args(NightRainLabelPlacer.DEFAULT_WEIGHTS).best
            // Any clear spot at all? Score only intrusion.
            val anyClear = args(NightRainLabelPlacer.Weights(centre = 0f, below = 0f, above = 0f, crowd = 0f)).best.clear
            if (anyClear) assertTrue("case $n: a clear spot exists but $best is not clear", best.clear)
        }
    }
}
