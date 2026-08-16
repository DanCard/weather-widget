package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.experimental.categories.Category

/**
 * Unit tests for the continuous-zoom span model in [DesktopGraphUtils]. The desktop temperature/
 * precip/cloud graphs derive their whole window (and label/smoothing cadence) from a single
 * `zoomFactor` in [0,1]; these tests pin the endpoints, monotonicity, and clamping.
 */
@Category(ShortDuration::class)
class DesktopGraphZoomTest {

    @Test
    fun `endpoints map to configured min and max spans`() {
        assertEquals(DesktopGraphUtils.MIN_BACK_HOURS, DesktopGraphUtils.backHoursFor(0f))
        assertEquals(DesktopGraphUtils.MAX_BACK_HOURS, DesktopGraphUtils.backHoursFor(1f))
        assertEquals(DesktopGraphUtils.MIN_FORWARD_HOURS, DesktopGraphUtils.forwardHoursFor(0f))
        assertEquals(DesktopGraphUtils.MAX_FORWARD_HOURS, DesktopGraphUtils.forwardHoursFor(1f))
    }

    @Test
    fun `max zoom-out is 30 days back and 7 days forward`() {
        assertEquals(720, DesktopGraphUtils.backHoursFor(1f))
        assertEquals(168, DesktopGraphUtils.forwardHoursFor(1f))
    }

    @Test
    fun `factor is clamped outside the unit interval`() {
        assertEquals(DesktopGraphUtils.MIN_BACK_HOURS, DesktopGraphUtils.backHoursFor(-2f))
        assertEquals(DesktopGraphUtils.MAX_BACK_HOURS, DesktopGraphUtils.backHoursFor(5f))
    }

    @Test
    fun `back and forward grow monotonically with the factor`() {
        var prevBack = DesktopGraphUtils.backHoursFor(0f)
        var prevForward = DesktopGraphUtils.forwardHoursFor(0f)
        var z = 0.1f
        while (z <= 1f) {
            val back = DesktopGraphUtils.backHoursFor(z)
            val forward = DesktopGraphUtils.forwardHoursFor(z)
            assertTrue("back should be non-decreasing at z=$z", back >= prevBack)
            assertTrue("forward should be non-decreasing at z=$z", forward >= prevForward)
            prevBack = back
            prevForward = forward
            z += 0.1f
        }
    }

    @Test
    fun `view is history-leaning - back outgrows forward at wide zoom`() {
        // At full zoom-out there is far more history than forecast.
        assertTrue(DesktopGraphUtils.backHoursFor(1f) > DesktopGraphUtils.forwardHoursFor(1f) * 2)
    }

    @Test
    fun `day-view zoom spans a full day`() {
        // A day-click frames the clicked day midnight->midnight; that requires back + forward == 24h
        // at the chosen factor. Guards against a future rescale of the zoom curve silently breaking
        // the full-day window (it is computed, not hardcoded).
        val zoom = DesktopGraphUtils.dayViewZoomFactor
        val span = DesktopGraphUtils.backHoursFor(zoom) + DesktopGraphUtils.forwardHoursFor(zoom)
        assertTrue("day-view span $span should be within 1h of 24", kotlin.math.abs(span - 24) <= 1)
    }

    @Test
    fun `label interval always divides 24 and widens with span`() {
        val tight = DesktopGraphUtils.labelIntervalFor(4)
        val wide = DesktopGraphUtils.labelIntervalFor(168)
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(4))
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(36))
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(168))
        assertTrue("wider spans need sparser labels", wide >= tight)
    }

    @Test
    fun `labelIntervalForWidth is denser on wider windows and always divides 24`() {
        val span = 12
        val narrow = DesktopGraphUtils.labelIntervalForWidth(span, widthPx = 200f, minLabelSpacingPx = 100f)
        val wide = DesktopGraphUtils.labelIntervalForWidth(span, widthPx = 1200f, minLabelSpacingPx = 100f)
        // Wider window -> more labels -> smaller (denser) interval.
        assertTrue("wider window should be at least as dense: wide=$wide narrow=$narrow", wide <= narrow)
        assertEquals(0, 24 % narrow)
        assertEquals(0, 24 % wide)
        // 1200px / 100px = 12 labels; a 12h span fits every hour (13 labels needs 13, so interval 2 -> 7).
        assertTrue("very wide window reaches fine cadence", wide <= 2)
    }

    @Test
    fun `labelIntervalForWidth falls back to hours-only table when width unknown`() {
        assertEquals(DesktopGraphUtils.labelIntervalFor(24), DesktopGraphUtils.labelIntervalForWidth(24, 0f, 100f))
        assertEquals(DesktopGraphUtils.labelIntervalFor(24), DesktopGraphUtils.labelIntervalForWidth(24, 700f, 0f))
    }

    @Test
    fun `labelIntervalForWidth caps at 24h for a very narrow window`() {
        // A 96h span in a window that fits only ~2 labels can't go denser than the coarsest step.
        val interval = DesktopGraphUtils.labelIntervalForWidth(96, widthPx = 120f, minLabelSpacingPx = 100f)
        assertEquals(24, interval)
    }

    @Test
    fun `smoothing increases with span`() {
        assertTrue(DesktopGraphUtils.smoothIterationsFor(168) >= DesktopGraphUtils.smoothIterationsFor(4))
    }

    @Test
    fun `legacy zoom strings migrate to a factor`() {
        assertEquals(0f, DesktopGraphUtils.zoomFactorFromLegacy("NARROW"))
        assertEquals(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR, DesktopGraphUtils.zoomFactorFromLegacy("WIDE"))
        assertEquals(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR, DesktopGraphUtils.zoomFactorFromLegacy(null))
    }

    @Test
    fun `nav jump is a sixth of the visible span and scales with zoom`() {
        var z = 0f
        while (z <= 1f) {
            val span = DesktopGraphUtils.totalSpanHoursFor(z)
            // Delegates to the shared rule now, so desktop and the Android widget step identically
            // at a given span: 1h through 8h (the configurable NARROW band), then a sixth of the
            // span above that.
            val expected = HourlyZoomRules.navJumpHours(span)
            assertEquals("jump should follow the shared rule at z=$z", expected, DesktopGraphUtils.navJumpHours(z))
            z += 0.1f
        }
        // Zoomed in -> small jump (doesn't overshoot the window); zoomed out -> large jump.
        assertTrue(
            "nav jump must grow as the view zooms out",
            DesktopGraphUtils.navJumpHours(1f) > DesktopGraphUtils.navJumpHours(0f),
        )
    }

    @Test
    fun `nav jump never stalls at the tightest zoom`() {
        // Even at the smallest span the arrow must move at least an hour.
        assertTrue(DesktopGraphUtils.navJumpHours(0f) >= 1)
    }

    @Test
    fun `pan drag direction and magnitude`() {
        // Drag right (positive px) reveals earlier time -> the hourly offset decreases.
        assertTrue(DesktopGraphUtils.panDeltaHours(100f, 800f, 24) < 0f)
        // A full-width drag pans the whole visible span.
        assertEquals(-24f, DesktopGraphUtils.panDeltaHours(800f, 800f, 24), 0.001f)
        // Zero-width guard (no NaN/division blow-up before the canvas is measured).
        assertEquals(0f, DesktopGraphUtils.panDeltaHours(50f, 0f, 24), 0f)
    }

    @Test
    fun `daily snap-step pans whole columns in the right direction`() {
        val dayWidth = 50f
        // Drag right (positive px) reveals earlier days -> the day offset decreases (negative).
        assertEquals(-1, DesktopGraphUtils.panDeltaDays(60f, dayWidth))
        // Partial column does not step yet (truncates toward zero).
        assertEquals(0, DesktopGraphUtils.panDeltaDays(40f, dayWidth))
        assertEquals(0, DesktopGraphUtils.panDeltaDays(-49f, dayWidth))
        // Multiple columns in one fast flick.
        assertEquals(-2, DesktopGraphUtils.panDeltaDays(125f, dayWidth))
        // Drag left (negative px) reveals later days -> the day offset increases (positive).
        assertEquals(1, DesktopGraphUtils.panDeltaDays(-55f, dayWidth))
        // Zero-width guard before the canvas is measured.
        assertEquals(0, DesktopGraphUtils.panDeltaDays(120f, 0f))
    }

    @Test
    fun `daily snap-step accumulator stays linear across columns`() {
        // The modifier removes consumed columns via `accum += steps * dayWidth`; after stepping, the
        // leftover accumulation must be the sub-column remainder so the next column lands correctly.
        val dayWidth = 50f
        var accum = 0f
        // Drag 130px right in one go: steps -2, remainder 30px still pending.
        accum += 130f
        val steps = DesktopGraphUtils.panDeltaDays(accum, dayWidth)
        accum += steps * dayWidth
        assertEquals(-2, steps)
        assertEquals(30f, accum, 0.001f)
        // 20px more crosses the third column boundary.
        accum += 20f
        val steps2 = DesktopGraphUtils.panDeltaDays(accum, dayWidth)
        accum += steps2 * dayWidth
        assertEquals(-1, steps2)
        assertEquals(0f, accum, 0.001f)
    }

    @Test
    fun `drag residual is zero at whole hours`() {
        assertEquals(0f, DesktopGraphUtils.dragResidualPx(0f, 40f), 0.001f)
        assertEquals(0f, DesktopGraphUtils.dragResidualPx(3f, 40f), 0.001f)
        assertEquals(0f, DesktopGraphUtils.dragResidualPx(-2f, 40f), 0.001f)
    }

    // --- Multi-day date labels ----------------------------------------------------------------

    @Test
    fun `formatDateLabel is weekday plus day-of-month`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            // 2026-06-10 is a Wednesday.
            assertEquals("Wed 10", DesktopGraphUtils.formatDateLabel(LocalDate.of(2026, 6, 10)))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `representativeIndicesByDay picks one noon-centered index per day`() {
        val utc = ZoneId.of("UTC")
        val base = Instant.parse("2026-06-10T00:00:00Z").toEpochMilli()
        val hourMs = 3_600_000L
        // 49 hourly points: all of Jun 10 (idx 0..23), all of Jun 11 (idx 24..47), Jun 12 00:00 (idx 48).
        val points = (0..48).map { i -> HourlyForecast(base + i * hourMs, 70f, "Clear") }

        val indices = DesktopGraphUtils.representativeIndicesByDay(points, utc)

        // One label per visible day; Jun 10/11 land on local noon, Jun 12's lone point is its rep.
        assertEquals(setOf(12, 36, 48), indices)
    }

    @Test
    fun `representativeIndicesByDay handles empty input`() {
        assertTrue(DesktopGraphUtils.representativeIndicesByDay(emptyList(), ZoneId.of("UTC")).isEmpty())
    }

    @Test
    fun `date label span threshold is beyond a two day window`() {
        // The default-ish near zoom (~24h span) keeps clock labels; only wider spans flip to dates.
        assertTrue(DesktopGraphUtils.DATE_LABEL_SPAN_THRESHOLD_HOURS >= 48)
    }

    @Test
    fun `footerLabels uses clock-hour cadence below the date threshold`() {
        val utc = ZoneId.of("UTC")
        val base = Instant.parse("2026-06-10T00:00:00Z").toEpochMilli()
        val hourMs = 3_600_000L
        // 24h span with a pixel budget of 7 labels -> width-aware interval 4, so a label every 4th
        // hour, time-of-day text. (700px / 100px per label = 7 labels; interval 4 yields 7 labels.)
        val points = (0..24).map { i -> HourlyForecast(base + i * hourMs, 70f, "Clear") }

        val labels = DesktopGraphUtils.footerLabels(
            points, totalSpanHours = 24, zone = utc, widthPx = 700f, minLabelSpacingPx = 100f,
        )

        assertEquals(listOf(0, 4, 8, 12, 16, 20, 24), labels.map { it.index })
        assertEquals(
            listOf("12a", "4a", "8a", "12p", "4p", "8p", "12a"),
            labels.map { it.text },
        )
    }

    @Test
    fun `footerLabels uses one centered date per day above the threshold`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val utc = ZoneId.of("UTC")
            val base = Instant.parse("2026-06-10T00:00:00Z").toEpochMilli()
            val hourMs = 3_600_000L
            // 72h span -> date mode: Jun 10/11 land on local noon, Jun 12's lone 00:00 point is its rep.
            val points = (0..48).map { i -> HourlyForecast(base + i * hourMs, 70f, "Clear") }

            // Date mode ignores width/spacing; pass arbitrary values.
            val labels = DesktopGraphUtils.footerLabels(
                points, totalSpanHours = 72, zone = utc, widthPx = 700f, minLabelSpacingPx = 100f,
            )

            assertEquals(listOf(12, 36, 48), labels.map { it.index })
            assertEquals(listOf("Wed 10", "Thu 11", "Fri 12"), labels.map { it.text })
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- Shared 3-stage zoom (click cycle) ----------------------------------------------------

    @Test
    fun `each stage maps to its canonical zoom factor`() {
        // WIDE lands on the existing default; THREE_DAY is wide. Canonical factors shifted when
        // MAX_BACK_HOURS grew to 720 (the curve rescaled): WIDE's 12h back now sits at ~0.304 and
        // THREE_DAY's 48h back at ~0.540. Neither moves with the narrow-span setting.
        assertEquals(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR, DesktopGraphUtils.zoomFactorForStage(ZoomStage.WIDE), 0.02f)
        assertEquals(0.54f, DesktopGraphUtils.zoomFactorForStage(ZoomStage.THREE_DAY), 0.02f)
        // NARROW is only the curve's floor (factor 0) at its minimum 4h span, which is 2h back.
        // Widening the setting walks it up the curve.
        assertEquals(0f, DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, 4), 0.001f)
        assertTrue(
            "a wider narrow span must map to a wider zoom factor",
            DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, 8) >
                DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, 4),
        )
    }

    @Test
    fun `narrow stage round-trips through the snap at every configurable span`() {
        // The desktop click cycle maps factor -> stage -> factor. If those two disagree about the
        // configured span, a click can advance from a stage the user isn't looking at.
        for (span in HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS) {
            val factor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, span)
            assertEquals(
                "narrow span=$span must snap back to NARROW",
                ZoomStage.NARROW,
                ZoomStage.nearestByTotalSpan(DesktopGraphUtils.totalSpanHoursFor(factor), span),
            )
        }
    }

    @Test
    fun `stage factor reproduces the stage back-hours`() {
        // The factor is the inverse of the back-hours curve, so round-tripping recovers each span.
        for (stage in ZoomStage.entries) {
            assertEquals(
                "back hours for $stage",
                stage.window().backHours.toInt(),
                DesktopGraphUtils.backHoursFor(DesktopGraphUtils.zoomFactorForStage(stage)),
            )
        }
    }

    @Test
    fun `stage factor reproduces the stage forward-hours too`() {
        // forwardHoursFor is anchored to the stage windows, so a stage's factor renders the whole
        // window and not just its history half. Before the anchors, WIDE's factor drew 8h forward
        // against the stage's 6h and THREE_DAY's drew 22h against 24h.
        for (stage in ZoomStage.entries) {
            assertEquals(
                "forward hours for $stage",
                stage.window().forwardHours.toInt(),
                DesktopGraphUtils.forwardHoursFor(DesktopGraphUtils.zoomFactorForStage(stage)),
            )
        }
    }

    @Test
    fun `the default factor renders the WIDE window`() {
        // What the popup opens on with a fresh config: the shared 18h WIDE window, 12h back / 6h
        // forward, identical to the Android widget's default.
        val wide = ZoomStage.WIDE.window()
        assertEquals(wide.backHours.toInt(), DesktopGraphUtils.backHoursFor(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR))
        assertEquals(wide.forwardHours.toInt(), DesktopGraphUtils.forwardHoursFor(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR))
        assertEquals(18, DesktopGraphUtils.totalSpanHoursFor(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR))
    }

    @Test
    fun `forward hours stay monotone across every anchor seam`() {
        // The anchored curve has kinks at the stage factors; a fine sweep catches a seam that steps
        // backwards (which would make a wheel notch shrink the forecast half of the view).
        var prev = DesktopGraphUtils.forwardHoursFor(0f)
        for (step in 0..1000) {
            val z = step / 1000f
            val forward = DesktopGraphUtils.forwardHoursFor(z)
            assertTrue("forward should be non-decreasing at z=$z (was $prev, got $forward)", forward >= prev)
            prev = forward
        }
        assertEquals(DesktopGraphUtils.MAX_FORWARD_HOURS, prev)
    }

    @Test
    fun `clicking from each stage factor advances exactly one stage`() {
        // Reproduces the Main onToggleZoom snap-then-next: nearest stage to the current span, then next().
        for (stage in ZoomStage.entries) {
            val factor = DesktopGraphUtils.zoomFactorForStage(stage)
            val landed = ZoomStage.nearestByTotalSpan(DesktopGraphUtils.totalSpanHoursFor(factor))
            assertEquals("snapping $stage's factor lands back on $stage", stage, landed)
            assertEquals("cycle advances one stage from $stage", stage.next(), landed.next())
        }
    }

    @Test
    fun `data step plus residual is perfectly linear in drag`() {
        // The on-screen slide = whole-hour data shift (-round(D)*pph) + sub-hour residual. The two
        // sum to exactly -D*pph for every D, which is what makes the drag continuous across hour
        // boundaries at any zoom.
        val pph = 40f
        for (d in listOf(0f, 0.3f, 0.49f, 0.51f, 0.99f, 1.0f, 1.5f, -0.7f, -2.4f)) {
            val dataStepPx = -d.roundToInt() * pph
            val net = dataStepPx + DesktopGraphUtils.dragResidualPx(d, pph)
            assertEquals("net slide at d=$d", -d * pph, net, 0.01f)
        }
    }
}
