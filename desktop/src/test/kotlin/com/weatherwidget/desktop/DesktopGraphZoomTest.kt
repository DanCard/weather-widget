package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.graph.ZoomStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Unit tests for the continuous-zoom span model in [DesktopGraphUtils]. The desktop temperature/
 * precip/cloud graphs derive their whole window (and label/smoothing cadence) from a single
 * `zoomFactor` in [0,1]; these tests pin the endpoints, monotonicity, and clamping.
 */
class DesktopGraphZoomTest {

    @Test
    fun `endpoints map to configured min and max spans`() {
        assertEquals(DesktopGraphUtils.MIN_BACK_HOURS, DesktopGraphUtils.backHoursFor(0f))
        assertEquals(DesktopGraphUtils.MAX_BACK_HOURS, DesktopGraphUtils.backHoursFor(1f))
        assertEquals(DesktopGraphUtils.MIN_FORWARD_HOURS, DesktopGraphUtils.forwardHoursFor(0f))
        assertEquals(DesktopGraphUtils.MAX_FORWARD_HOURS, DesktopGraphUtils.forwardHoursFor(1f))
    }

    @Test
    fun `max zoom-out is 6 days back and 1 day forward`() {
        assertEquals(144, DesktopGraphUtils.backHoursFor(1f))
        assertEquals(24, DesktopGraphUtils.forwardHoursFor(1f))
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
    fun `label interval always divides 24 and widens with span`() {
        val tight = DesktopGraphUtils.labelIntervalFor(4)
        val wide = DesktopGraphUtils.labelIntervalFor(168)
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(4))
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(36))
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(168))
        assertTrue("wider spans need sparser labels", wide >= tight)
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
    fun `nav jump is half the visible span and scales with zoom`() {
        var z = 0f
        while (z <= 1f) {
            val span = DesktopGraphUtils.totalSpanHoursFor(z)
            assertEquals("jump should be half the span at z=$z", (span / 2).coerceAtLeast(1), DesktopGraphUtils.navJumpHours(z))
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
        // 24h span -> labelIntervalFor(24) == 4, so a label every 4th hour, time-of-day text.
        val points = (0..24).map { i -> HourlyForecast(base + i * hourMs, 70f, "Clear") }

        val labels = DesktopGraphUtils.footerLabels(points, totalSpanHours = 24, zone = utc)

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

            val labels = DesktopGraphUtils.footerLabels(points, totalSpanHours = 72, zone = utc)

            assertEquals(listOf(12, 36, 48), labels.map { it.index })
            assertEquals(listOf("Wed 10", "Thu 11", "Fri 12"), labels.map { it.text })
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- Shared 3-stage zoom (click cycle) ----------------------------------------------------

    @Test
    fun `each stage maps to its canonical zoom factor`() {
        // NARROW is the tightest (factor 0); WIDE lands on the existing default; THREE_DAY is wide.
        assertEquals(0f, DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW), 0.001f)
        assertEquals(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR, DesktopGraphUtils.zoomFactorForStage(ZoomStage.WIDE), 0.02f)
        assertEquals(0.74f, DesktopGraphUtils.zoomFactorForStage(ZoomStage.THREE_DAY), 0.02f)
    }

    @Test
    fun `stage factor reproduces the stage back-hours`() {
        // The factor is the inverse of the back-hours curve, so round-tripping recovers each span.
        for (stage in ZoomStage.entries) {
            assertEquals(
                "back hours for $stage",
                stage.backHours.toInt(),
                DesktopGraphUtils.backHoursFor(DesktopGraphUtils.zoomFactorForStage(stage)),
            )
        }
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
