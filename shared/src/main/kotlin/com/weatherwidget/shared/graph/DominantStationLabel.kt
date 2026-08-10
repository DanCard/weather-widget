package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.TempUtils

/**
 * The "which thermometer is actually driving this line?" annotation on the hourly temperature graph:
 * the station holding the largest share of the observation blend, and what it read — e.g. `knuq 73.4°`.
 *
 * The observed line is an IDW blend of every nearby station, so on a normal day it matches no single
 * thermometer. This label names the one that dominates the mix, which is the difference between "the
 * app is wrong" and "the app is averaging in a station two towns over."
 *
 * - **Text**: lowercased station id, a space, then the station's RAW reading through
 *   [TempUtils.formatTemp] — the same value and formatter behind the daily today-column overlay's
 *   station row ([com.weatherwidget.shared.actuals.BlendTableFormatter.formatDominantTempAgeRows]),
 *   so the two surfaces can never disagree by a rounding rule. Raw, not the value fed to the blend:
 *   an extrapolated value is a forecast in disguise and naming a station beside it would be a lie.
 * - **Visibility**: gated by [MAX_HOURS_SPAN] and by there being room. Zoomed-out views are excluded
 *   (see the constant), and a plot with no clear band simply gets no label — this is context, never
 *   worth pushing another number off the graph for.
 * - **Position**: empty space, via [GraphEmptySpaceFinder], preferring the plot's edges.
 *
 * Each platform supplies its own measured metrics and a sampler of the *visible* curve, so this stays
 * free of android.graphics / Compose types.
 */
object DominantStationLabel {

    /**
     * Show the label for windows up to this span. 25 h admits the WIDE view (24 h) and every NARROW
     * span, and excludes the 3-day view (72 h) — where the observed line covers days and "the dominant
     * station right now" says nothing about most of what is on screen. Desktop's zoom is continuous
     * rather than staged, and the same span test retires the label there as it widens.
     *
     * Numerically equal to [ForecastDeltaLabel.DELTA_LABEL_MAX_HOURS_SPAN] but deliberately its own
     * constant: "is this window short enough for a forecast comparison?" and "…for a single station to
     * be representative?" are different questions that happen to share an answer today.
     */
    const val MAX_HOURS_SPAN = 25L

    /**
     * Edge-first, the mirror of [ForecastDeltaLabel.X_FRACTIONS]. The delta label is placed first and
     * registers itself as an obstacle, so the two can never actually overlap; opposite preferences just
     * stop them landing shoulder-to-shoulder in the middle of an otherwise empty plot.
     */
    val X_FRACTIONS = listOf(0.22f, 0.78f, 0.35f, 0.65f, 0.5f)

    /**
     * A placed label, in both coordinate conventions: [centerX] + [baselineY] for a center-aligned
     * Android `Canvas.drawText`, and [box] (top-left) for Compose `drawText(topLeft = box.topLeft)`.
     */
    data class Placement(
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val box: GraphRect,
    )

    /**
     * `knuq 73.4°`. Lowercase because at this font size an all-caps callsign shouts louder than the
     * temperatures it sits among. Returns null when there is nothing to name.
     */
    fun format(stationId: String?, rawTemp: Float?, useCelsius: Boolean): String? {
        val id = stationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val temp = TempUtils.formatTemp(rawTemp, useCelsius) ?: return null
        return "${id.lowercase()} $temp"
    }

    /**
     * Places already-[format]ted [text], or returns null when it should not be drawn (nothing to say,
     * window too wide, or no empty band fits).
     *
     * Takes the finished string rather than id + temperature because the platforms resolve it at
     * different points: Android formats in the widget state resolver (the only layer holding both the
     * blend result and the unit preference) and hands the renderer a string, while desktop formats
     * inline. [plot] is the curve-drawing area (exclude the footer). [drawnBounds] are obstacles already
     * on the canvas. [curveYsAt] returns the y of every line drawn at a given x — see
     * [GraphEmptySpaceFinder.find] for why that must be plural. [padPx] is the minimum clearance kept on
     * all sides.
     */
    fun place(
        text: String?,
        spanHours: Long,
        plot: GraphRect,
        drawnBounds: List<GraphRect>,
        curveYsAt: (Float) -> List<Float>,
        metrics: GraphEmptySpaceFinder.Metrics,
        padPx: Float,
        maxSpanHours: Long = MAX_HOURS_SPAN,
    ): Placement? {
        if (spanHours > maxSpanHours) return null
        if (text.isNullOrBlank()) return null

        val slot =
            GraphEmptySpaceFinder.find(
                plot = plot,
                drawnBounds = drawnBounds,
                curveYsAt = curveYsAt,
                metrics = metrics,
                padPx = padPx,
                xFractions = X_FRACTIONS,
            ) ?: return null

        return Placement(
            text = text,
            centerX = slot.centerX,
            baselineY = slot.baselineY,
            box = slot.box,
        )
    }
}
