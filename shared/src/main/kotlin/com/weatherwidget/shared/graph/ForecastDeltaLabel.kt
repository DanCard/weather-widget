package com.weatherwidget.shared.graph

import kotlin.math.abs
import kotlin.math.round

/**
 * Pure, platform-free formatting + placement for the "+0.4 from forecast" delta label drawn on the
 * zoomed-in hourly temperature graph. The number itself is the forecast delta (observed minus
 * forecast at the current hour, the same value that shifts the ghost line); this object owns how it
 * reads, what color it is, when it shows, and where it sits — so Android and desktop stay
 * pixel-consistent.
 *
 * - **Text**: signed, one decimal, e.g. `+0.4 from forecast`, `-1.2 from forecast`. A delta that
 *   rounds to zero (e.g. `+0.0 from forecast`) is never shown — see [isZero].
 * - **Color**: the thermostat gradient ([TemperatureColorModel]) evaluated at the *current* temperature,
 *   so the label harmonizes with the curve color at "now".
 * - **Visibility**: the narrow AND the 24 h view, gated by [DELTA_LABEL_MAX_HOURS_SPAN] (25 h — admits a
 *   full-day window, excludes the 3-day view), and only when a delta exists. This is intentionally wider
 *   than the fetch-dot age gate ([FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN], 12 h): "is this fresh enough to
 *   show an age?" and "is this zoomed in enough for a forecast comparison?" are different questions.
 * - **Position**: dropped into empty space — a vertical band in the plot, preferring the visual center,
 *   that clears both the temperature curve and every already-placed label/icon/fetch-dot.
 *
 * Each platform supplies its own measured [Metrics] (from its staleness paint) and a sampler of every
 * curve drawn at a given x, so this stays free of android.graphics / Compose types.
 */
object ForecastDeltaLabel {
    const val SUFFIX = " from forecast"
    const val COMPACT_CAPTION = "fcst"

    /**
     * Show the label for windows up to this span: admits the narrow view and the 24 h view (span 24 h),
     * excludes the 3-day view (span 72 h). Deliberately wider than the 12 h fetch-dot age gate.
     */
    const val DELTA_LABEL_MAX_HOURS_SPAN = 25L

    /**
     * Number of x-anchors tried, in order; the first that yields any clear band wins (center first).
     * [DominantStationLabel] deliberately takes the opposite preference (left/right edges first, plus
     * a left-hugging lead anchor) so the two labels drift to opposite ends of an empty plot instead of
     * landing shoulder-to-shoulder.
     */
    val X_FRACTIONS = listOf(0.5f, 0.35f, 0.65f, 0.22f, 0.78f)

    /** Vertical search resolution: candidate top positions stepped through the plot band. */
    const val VERTICAL_STEPS = GraphEmptySpaceFinder.VERTICAL_STEPS

    fun format(delta: Float, useCelsius: Boolean, suffix: String = SUFFIX): String {
        return formatValue(delta, useCelsius) + suffix
    }

    /**
     * True when the delta formats to zero (e.g. `+0.0 from forecast` / `-0.0 from forecast`).
     * A zero delta carries no comparison information, so the on-graph label is suppressed.
     */
    fun isZero(delta: Float, useCelsius: Boolean): Boolean {
        val displayDelta = if (useCelsius) delta / 1.8f else delta
        return round(displayDelta * 10f).toInt() == 0
    }

    /** Signed numeric portion, shared by the one-line hourly label and multi-line daily overlay. */
    fun formatValue(delta: Float, useCelsius: Boolean): String {
        // The delta is a temperature *difference* in °F: convert by scaling only (no −32 offset).
        val displayDelta = if (useCelsius) delta / 1.8f else delta
        val tenths = round(displayDelta * 10f).toInt() // round-half-up at the tenths place
        val sign = if (tenths >= 0) "+" else "-"
        val mag = abs(tenths)
        return "$sign${mag / 10}.${mag % 10}"
    }

    fun colorArgb(currentTemp: Float): Int = TemperatureColorModel.tempToColorArgb(currentTemp)

    /** Text metrics from the platform's staleness paint. [ascent] is negative, [descent] positive. */
    data class Metrics(val width: Float, val ascent: Float, val descent: Float) {
        val height: Float get() = descent - ascent
    }

    /**
     * A placed label, in both coordinate conventions: [centerX] + [baselineY] for a center-aligned
     * Android `Canvas.drawText`, and [box] (top-left) for Compose `drawText(topLeft = box.topLeft)`.
     */
    data class Placement(
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val box: GraphRect,
        val colorArgb: Int,
    )

    /**
     * Resolves the label, or null when it should not be drawn (no delta / current temp, window too wide,
     * or no empty band fits). [plot] is the curve-drawing area (exclude the footer). [drawnBounds] are
     * obstacles already on the canvas (labels, icons, fetch-dot). [curveYsAt] returns the y of every line
     * drawn at a given x — see [GraphEmptySpaceFinder.find] for why that must be plural. [padPx] is the
     * minimum clearance kept on all sides. [vetoBounds] are draw-over-nothing obstacles that exert no
     * repulsion — the NOW indicator line; see [GraphEmptySpaceFinder.find].
     */
    fun place(
        delta: Float?,
        currentTemp: Float?,
        spanHours: Long,
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYsAt: (Float) -> List<Float>,
        metrics: Metrics,
        padPx: Float,
        useCelsius: Boolean,
        suffix: String = SUFFIX,
        maxSpanHours: Long = DELTA_LABEL_MAX_HOURS_SPAN,
        vetoBounds: List<GraphRect> = emptyList(),
    ): Placement? {
        if (delta == null || currentTemp == null) return null
        if (isZero(delta, useCelsius)) return null
        if (spanHours > maxSpanHours) return null

        val slot =
            GraphEmptySpaceFinder.find(
                plot = plot,
                drawnBounds = drawnBounds,
                curveYsAt = curveYsAt,
                metrics = GraphEmptySpaceFinder.Metrics(metrics.width, metrics.ascent, metrics.descent),
                padPx = padPx,
                xFractions = X_FRACTIONS,
                vetoBounds = vetoBounds,
            ) ?: return null

        return Placement(
            text = format(delta, useCelsius, suffix),
            centerX = slot.centerX,
            baselineY = slot.baselineY,
            box = slot.box,
            colorArgb = colorArgb(currentTemp),
        )
    }
}
