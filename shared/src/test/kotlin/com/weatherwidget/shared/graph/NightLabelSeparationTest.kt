package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.DailyRainLabelPlanner.Box
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Neighbouring night labels are kept apart: the right one slides right, then rises, and only then
 * does the left one drop. Geometry from the emulator trace of 2026-11-06 19:27 (Silurian, canvas
 * 559 px at 2 px/dp): Fri ".24in" and Sat "100%" overlapped by ~6 px at the same height.
 * (plans/261106-night-rain-labels-overlap-each-other.md)
 */
@Category(ShortDuration::class)
class NightLabelSeparationTest {
    private val p = 2f
    private val dayLabelTop = 384.3f

    private val friIcon = Box(475.1f, 252.9f, 506.1f, 283.9f)
    private val friLow = listOf(
        Box(473.1f, 283.6f, 499.1f, 311.7f, isLowDigits = true),
        Box(499.1f, 283.6f, 508.1f, 297.7f, isDegreeSign = true),
    )
    private val satIcon = Box(520.7f, 233.6f, 551.7f, 264.6f)
    private val satLow = listOf(
        Box(518.7f, 264.3f, 544.7f, 292.5f, isLowDigits = true),
        Box(544.7f, 264.3f, 553.7f, 278.4f, isDegreeSign = true),
    )

    private fun input(date: String, text: String, obstacles: List<Box>) = DailyRainLabelLayout.NightInput(
        date = date, text = text, probability = 100, isPast = false, daysFromToday = 7,
        columnCenterX = 0f, gapCenterX = 0f, columnWidth = 45.6f,
        leftLowLabel = Box(0f, 0f, 0f, 0f), rightLowLabel = null, obstacles = obstacles,
        dayLabelTop = dayLabelTop, canvasWidth = 559f, pxPerDp = p,
    )

    private fun placed(text: String, centerX: Float, top: Float, width: Float) = DailyRainLabelLayout.Placed(
        DailyRainLabelLayout.Kind.NIGHT, text, 1f, centerX, top, width, 15.2f, top + 11.5f, "NIGHT_SHIFTED_LEFT", 0f,
    )

    private val fri = DailyRainLabelLayout.LaidOutNight(
        input("2026-11-13", ".24in", listOf(friIcon) + friLow + listOf(satIcon) + satLow),
        placed(".24in", centerX = 513.4f, top = 299.7f, width = 27f),
    )
    private val sat = DailyRainLabelLayout.LaidOutNight(
        input("2026-11-14", "100%", listOf(satIcon) + satLow),
        placed("100%", centerX = 536f, top = 297.5f, width = 30f),
    )

    private fun overlaps(a: DailyRainLabelLayout.Placed, b: DailyRainLabelLayout.Placed) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun DailyRainLabelLayout.Placed.box() = "(${left},${top},${right},${bottom})"

    @Test
    fun `emulator Fri and Sat night labels come apart - right, up, then the amount drops the rest`() {
        assert(overlaps(fri.label, sat.label)) { "fixture must start overlapping" }
        val (f, s) = DailyRainLabelLayout.separateNightLabels(listOf(fri, sat), "test")
        println("Fri ${fri.label.box()} -> ${f.box()}; Sat ${sat.label.box()} -> ${s.box()}")
        assertFalse("labels still overlap", overlaps(f, s))
        // Right to 1 dp from the canvas edge (559 - 2 px): 551 -> 557.
        assertEquals(557f, s.right, 0.01f)
        // Up until 1 dp under the ink of Sat's "57" (digit box bottom 292.5 minus 22 % blank descent).
        val satInkBottom = 292.5f - (292.5f - 264.3f) * 0.22f
        assertEquals("rises to 1 dp under the digits' ink", true, s.top >= satInkBottom + p && s.top < satInkBottom + p + p)
        assertEquals(sat.label.baseline - (sat.label.top - s.top), s.baseline, 0.01f)
        // Fri drops only what is left: 1 dp under Sat's label.
        assertEquals(s.bottom + p, f.top, 0.01f)
        assertEquals(fri.label.centerX, f.centerX, 0.01f)
        assert(f.top - fri.label.top < 15.2f) { "the amount moved less than a full label height" }
    }

    @Test
    fun `labels that do not overlap are left exactly where they were`() {
        val farSat = sat.copy(label = sat.label.copy(centerX = 545f, top = 330f, baseline = 341.5f))
        val out = DailyRainLabelLayout.separateNightLabels(listOf(fri, farSat), "test")
        assertSame(fri.label, out[0])
        assertSame(farSat.label, out[1])
    }

    @Test
    fun `with no room right or above the left label drops the whole way`() {
        // An icon right above Sat's label and the label already on the edge: only the drop is left.
        val boxedIn = sat.copy(
            input = sat.input.copy(obstacles = listOf(Box(520f, 280f, 560f, 296f))),
            label = sat.label.copy(centerX = 542f, baseline = 309f),
        )
        val (f, s) = DailyRainLabelLayout.separateNightLabels(listOf(fri, boxedIn), "test")
        println("boxed in: Fri -> ${f.box()}; Sat -> ${s.box()}")
        assertEquals(boxedIn.label.top, s.top, 0.01f)
        assertEquals(s.bottom + p, f.top, 0.01f)
        assertFalse(overlaps(f, s))
    }

    @Test
    fun `the left label never drops into the day-name row`() {
        val low = fri.copy(label = fri.label.copy(top = 360f, baseline = 371.5f))
        val lowSat = sat.copy(
            input = sat.input.copy(obstacles = listOf(Box(520f, 340f, 560f, 357f))),
            label = sat.label.copy(centerX = 542f, top = 358f, baseline = 369.5f),
        )
        val (f, _) = DailyRainLabelLayout.separateNightLabels(listOf(low, lowSat), "test")
        println("floor: Fri -> ${f.box()} dayLabelTop=$dayLabelTop")
        assert(f.bottom <= dayLabelTop) { "Fri bottom ${f.bottom} crossed the day-name row $dayLabelTop" }
    }
}
