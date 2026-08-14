package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.graph.ZoomStage
import java.time.ZoneId
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Settings → "Hourly Zoom" promises a number of hours. This walks the whole desktop chain that
 * promise travels through and checks the graph actually renders that many:
 *
 * ```
 * DesktopConfig.settings.narrowZoomSpanHours   (what the user typed)
 *   → zoomFactorForStage(NARROW, span) (what the popup click stores as config.zoomFactor)
 *     → backHoursFor / forwardHoursFor  (what the renderers turn that factor into)
 *       → start..cutoff                 (the window the hourly graphs actually draw)
 * ```
 *
 * Existing coverage stops one link short: `DesktopGraphZoomTest` round-trips the factor against
 * *back* hours and checks the stage snaps back to NARROW, but nothing asserted the rendered
 * back+forward total. That gap hid a real defect — forward hours ride a different geometric curve
 * (`MAX_FORWARD_HOURS` 168 vs `MAX_BACK_HOURS` 720), so inverting against back hours alone let the
 * two disagree. At the **default** 5 h setting the graph rendered 3 back + 3 forward = 6 h, and at
 * 8 h it rendered 7 h.
 *
 * The desktop zoom is continuous, so this is the only stage that owes the user an exact number —
 * WIDE and THREE_DAY are just points on the curve and are deliberately not asserted here.
 */
@Category(ShortDuration::class)
class NarrowZoomSpanDisplayedHoursTest {

    private val configurableSpans =
        HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS

    /** Fixed instant on an exact hour; the window math is offset-only. */
    private val centerMs = 1_754_000_000_000L / 3_600_000L * 3_600_000L

    /** A real [DesktopConfig]; lat/lon/label are required but irrelevant to window geometry. */
    private fun config(
        narrowZoomSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
        zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
    ) = DesktopConfig(
lat = 37.42,
lon = -122.08,
label = "Test",
zoomFactor = zoomFactor,
settings = DesktopSettings(narrowZoomSpanHours = narrowZoomSpanHours),
)

    /** The window the hourly graphs draw, computed exactly as `rememberHourlyGraphSetup` does. */
    private data class RenderedWindow(val backHours: Int, val forwardHours: Int, val startMs: Long, val cutoffMs: Long) {
        val totalHours: Int get() = backHours + forwardHours
        val spanMs: Long get() = cutoffMs - startMs
    }

    private fun renderedWindow(config: DesktopConfig, centerMs: Long): RenderedWindow {
        val backHours = DesktopGraphUtils.backHoursFor(config.zoomFactor)
        val forwardHours = DesktopGraphUtils.forwardHoursFor(config.zoomFactor)
        return RenderedWindow(
            backHours = backHours,
            forwardHours = forwardHours,
            startMs = centerMs - backHours * 3_600_000L,
            cutoffMs = centerMs + forwardHours * 3_600_000L,
        )
    }

    /**
     * The popup's body-tap zoom cycle, verbatim from `Main.kt`'s `handleToggleZoom`: snap the current
     * continuous factor to the nearest stage, advance one stage, store that stage's factor.
     */
    private fun tapToggleZoom(config: DesktopConfig): DesktopConfig {
        val current = ZoomStage.nearestByTotalSpan(
            DesktopGraphUtils.totalSpanHoursFor(config.zoomFactor),
            config.settings.narrowZoomSpanHours,
        )
        val next = current.next()
        return config.copy(
            zoomFactor = DesktopGraphUtils.zoomFactorForStage(next, config.settings.narrowZoomSpanHours),
        )
    }

    /**
     * Hourly data on exact hour marks around [centerMs], the shape every provider returns.
     */
    private fun hourlySeries(centerMs: Long, hoursEitherSide: Int = 48): List<HourlyForecast> =
        (-hoursEitherSide..hoursEitherSide).map { h ->
            HourlyForecast(
                dateTime = centerMs / 3_600_000L * 3_600_000L + h * 3_600_000L,
                temperature = 60f,
                condition = "Clear",
                precipProbability = 0,
            )
        }

    /**
     * The span the graph actually PAINTS, not the span it queries.
     *
     * This is the link the first version of this test was missing. `xAtTime` maps
     * `points.first()..points.last()` across the full canvas width — deliberately, so the curve
     * reaches both edges — so the visible axis is the DATA span, and any point the window filter
     * drops shortens the graph. Asserting `cutoff - start` (as this test originally did) checks the
     * query and silently passes while the user counts an hour fewer on screen.
     */
    private fun drawnSpanHours(config: DesktopConfig, centerMs: Long): Long {
        val backHours = DesktopGraphUtils.backHoursFor(config.zoomFactor)
        val forwardHours = DesktopGraphUtils.forwardHoursFor(config.zoomFactor)
        val window = temperatureGraphHourWindow(centerMs, backHours, forwardHours, ZoneId.of("UTC"))
        val points = hourlyPointsInWindow(
            hourlySeries(centerMs),
            window.startMs,
            window.endMs,
            backHours + forwardHours,
        )
        return (points.last().dateTime - points.first().dateTime) / 3_600_000L
    }

    @Test
    fun `the graph PAINTS exactly the hours configured in settings`() {
        // The user-facing assertion: count the hours on screen, not the hours queried.
        configurableSpans.forEach { span ->
            val narrow = config(
                narrowZoomSpanHours = span,
                zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, span),
            )
            assertEquals(
                "settings say ${span}h; the drawn axis must span ${span}h",
                span.toLong(),
                drawnSpanHours(narrow, centerMs),
            )
        }
    }

    @Test
    fun `the drawn span matches the queried window`() {
        // The window and the paint must not disagree — that gap is what shipped a 5h graph for a 6h
        // setting. Checks the invariant across the whole zoom curve, not just the NARROW band.
        listOf(0f, 0.038f, 0.051f, 0.12f, DesktopGraphUtils.DEFAULT_ZOOM_FACTOR, 0.54f, 0.8f).forEach { z ->
            val backHours = DesktopGraphUtils.backHoursFor(z)
            val forwardHours = DesktopGraphUtils.forwardHoursFor(z)
            val window = temperatureGraphHourWindow(centerMs, backHours, forwardHours, ZoneId.of("UTC"))
            val points = hourlyPointsInWindow(
                hourlySeries(centerMs, hoursEitherSide = backHours + forwardHours + 24),
                window.startMs,
                window.endMs,
                backHours + forwardHours,
            )
            val drawn = (points.last().dateTime - points.first().dateTime) / 3_600_000L
            assertEquals(
                "at zoomFactor=$z the window is ${backHours + forwardHours}h but the graph paints ${drawn}h",
                (backHours + forwardHours).toLong(),
                drawn,
            )
        }
    }

    @Test
    fun `both window endpoints are included`() {
        // The asymmetry behind the bug: start was inclusive, end exclusive, so the last hour mark —
        // which temperatureGraphHourWindow builds as part of the view — was dropped.
        val backHours = 3
        val forwardHours = 3
        val window = temperatureGraphHourWindow(centerMs, backHours, forwardHours, ZoneId.of("UTC"))
        val points = hourlyPointsInWindow(hourlySeries(centerMs), window.startMs, window.endMs, 6)
        assertEquals("first point must be the window start", window.startMs, points.first().dateTime)
        assertEquals("last point must be the window end", window.endMs, points.last().dateTime)
        assertEquals("a 6h window on hourly data is 7 points", 7, points.size)
    }

    @Test
    fun `narrow view renders exactly the hours configured in settings`() {
        val centerMs = 1_754_000_000_000L // fixed instant; the window math is offset-only
        configurableSpans.forEach { span ->
            // 1. The user sets the span in Settings.
            val configured = config(narrowZoomSpanHours = span)
            // 2. They click the graph until it reaches the tight view.
            val narrow = configured.copy(
                zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, span),
            )
            // 3. The graph renders.
            val window = renderedWindow(narrow, centerMs)

            assertEquals(
                "settings say ${span}h but the narrow view renders " +
                    "${window.backHours}h back + ${window.forwardHours}h forward = ${window.totalHours}h",
                span,
                window.totalHours,
            )
            assertEquals(
                "the drawn window ${window.startMs}..${window.cutoffMs} must span exactly ${span}h",
                span * 3_600_000L,
                window.spanMs,
            )
        }
    }

    @Test
    fun `the default span is honoured out of the box`() {
        // Worth its own case: the defect this guards against was live at the default, so every fresh
        // install rendered an hour more than Settings claimed.
        val fresh = config()
        assertEquals(HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS, fresh.settings.narrowZoomSpanHours)

        val narrow = fresh.copy(
            zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, fresh.settings.narrowZoomSpanHours),
        )
        assertEquals(
            "a fresh install must render its default ${fresh.settings.narrowZoomSpanHours}h",
            fresh.settings.narrowZoomSpanHours,
            renderedWindow(narrow, 1_754_000_000_000L).totalHours,
        )
    }

    @Test
    fun `clicking through the zoom cycle lands on the configured span`() {
        // The real path a user takes: the popup opens at WIDE, and taps cycle
        // WIDE -> NARROW -> THREE_DAY -> WIDE. Whichever tap lands on NARROW must show the setting.
        configurableSpans.forEach { span ->
            var current = config(narrowZoomSpanHours = span, zoomFactor = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR)
            var sawNarrow = false
            repeat(3) {
                current = tapToggleZoom(current)
                val stage = ZoomStage.nearestByTotalSpan(
                    DesktopGraphUtils.totalSpanHoursFor(current.zoomFactor),
                    span,
                )
                if (stage == ZoomStage.NARROW) {
                    sawNarrow = true
                    assertEquals(
                        "after tapping into the narrow view, settings=${span}h must be what is drawn",
                        span,
                        renderedWindow(current, 1_754_000_000_000L).totalHours,
                    )
                }
            }
            assertTrue("the tap cycle must reach NARROW within one full cycle (span=$span)", sawNarrow)
        }
    }

    @Test
    fun `back and forward split the configured span the way the shared rule says`() {
        // ZoomStage splits odd spans back-heavy (ceil/floor). Desktop reaches its window through the
        // continuous curve instead, so this checks the two agree rather than merely summing correctly
        // — a 1-back/4-forward window would total 5h and still be wrong.
        configurableSpans.forEach { span ->
            val expected = ZoomStage.NARROW.window(span)
            val window = renderedWindow(
                config(
                    narrowZoomSpanHours = span,
                    zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, span),
                ),
                1_754_000_000_000L,
            )
            assertEquals("back hours at span=$span", expected.backHours.toInt(), window.backHours)
            assertEquals("forward hours at span=$span", expected.forwardHours.toInt(), window.forwardHours)
        }
    }

    @Test
    fun `an out of range configured span is clamped, not rendered literally`() {
        // config.json is a plain file a user can hand-edit. Whatever it says, the rendered window must
        // still be a legal span rather than a 0h or 40h "narrow" view.
        listOf(-5, 0, 1, 3, 9, 48, 1000).forEach { raw ->
            val window = renderedWindow(
                config(
                    narrowZoomSpanHours = raw,
                    zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, raw),
                ),
                1_754_000_000_000L,
            )
            assertEquals(
                "narrowZoomSpanHours=$raw must clamp into the legal band before rendering",
                HourlyZoomRules.clampNarrowSpan(raw),
                window.totalHours,
            )
        }
    }
}
