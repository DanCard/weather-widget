package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.WidgetQueryWindows

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Duration
import java.time.LocalDateTime

/**
 * Pins the contract between the forecast QUERY window and the blend's CONTEXT window.
 *
 * Field case (2026-07-15, Samsung): a scrolled-back hourly graph alternated between two curves about a
 * minute apart — flat with no high/low labels, then inclined with them — on a window whose hours had no
 * new observations. Two loaders fed the same resolver with different forecast coverage for the same
 * widget and centre:
 *   WidgetIntentRouter -> GraphDataLoader          -> 7   forecasts (visible ±2h)  -> flat, no labels
 *   WeatherWidgetProvider -> WidgetRenderer        -> 226 forecasts (now ± 72/168h) -> inclined, labels
 *
 * Cause: [GraphDataLoader.buildGraphQueryWindow] sized its query to the VISIBLE span
 * (zoom.backHours/forwardHours), but [com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder]
 * blends across contextLookback/LookaheadHours and extrapolates stations forward through the forecast
 * delta, which needs a forecast AT the anchor and AT the target
 * (`forecastTemperatureAt(...) ?: return null`). With only the visible hours queried, that lookup
 * returned null across nearly the whole context, so every extrapolating station dropped out of the
 * blend and the observed curve flattened — losing the interior extrema the labels anchor to.
 *
 * The invariant: the query window must cover the blend context at EVERY zoom, so both render paths
 * hand the resolver the same forecast coverage and cannot disagree about the curve.
 */
@Category(ShortDuration::class)
class GraphQueryWindowCoversBlendContextTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 15, 4, 0)

    @Test
    fun `query window covers the blend context at every zoom`() {
        // The centre the blend aligns to, mirroring build()/loadGraphHours' rounding.
        val centre = LocalDateTime.of(2026, 7, 14, 23, 24, 17)
        val rounded = LocalDateTime.of(2026, 7, 14, 23, 0)

        ZoomStage.entries.forEach { stage ->
            val zoom = stage.window()
            val window = GraphDataLoader.buildGraphQueryWindow(centre, zoom, now)

            val requiredStart = rounded.minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS)
            val requiredEnd = rounded.plusHours(WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS)

            assertTrue(
                "zoom=$zoom query start ${window.centerStart} must reach back to the blend context " +
                    "start $requiredStart (HOURLY_LOOKBACK_HOURS=${WidgetQueryWindows.HOURLY_LOOKBACK_HOURS})",
                !window.centerStart.isAfter(requiredStart),
            )
            assertTrue(
                "zoom=$zoom query end ${window.centerEnd} must reach forward to the blend context " +
                    "end $requiredEnd (HOURLY_LOOKAHEAD_HOURS=${WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS})",
                !window.centerEnd.isBefore(requiredEnd),
            )
        }
    }

    @Test
    fun `NARROW zoom no longer collapses to the visible span`() {
        // The exact field configuration: NARROW (±2h) scrolled ~5h into the past. Before the fix this
        // produced 21:00..05:00 — 8 hours, 7 forecast rows — starving the blend's 132h context.
        val centre = LocalDateTime.of(2026, 7, 14, 23, 24, 17)
        val window = GraphDataLoader.buildGraphQueryWindow(centre, ZoomStage.NARROW.window(), now)

        val spanHours = Duration.between(window.centerStart, window.centerEnd).toHours()
        val visibleSpan = ZoomStage.NARROW.window().backHours + ZoomStage.NARROW.window().forwardHours

        assertTrue(
            "NARROW query span ${spanHours}h must exceed the visible ${visibleSpan}h — sizing the query " +
                "to the visible window is what dropped extrapolating stations from the blend",
            spanHours > visibleSpan,
        )
        assertTrue(
            "NARROW query span ${spanHours}h must cover the full blend context " +
                "(${WidgetQueryWindows.HOURLY_LOOKBACK_HOURS}+${WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS}h)",
            spanHours >= WidgetQueryWindows.HOURLY_LOOKBACK_HOURS + WidgetQueryWindows.HOURLY_LOOKAHEAD_HOURS,
        )
    }

    @Test
    fun `both render paths request the same coverage for the same centre`() {
        // The provider path queries now ± lookback/graph-lookahead; the interaction path queries around
        // the scrolled centre. For a centre at "now" the two must agree on lookback depth, or the same
        // widget keeps rendering two different curves depending on which loader ran last.
        val window = GraphDataLoader.buildGraphQueryWindow(now, ZoomStage.NARROW.window(), now)
        val providerStart = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS)

        assertTrue(
            "interaction-path start ${window.centerStart} must reach at least as far back as the " +
                "provider path's $providerStart",
            !window.centerStart.isAfter(providerStart),
        )
    }
}
