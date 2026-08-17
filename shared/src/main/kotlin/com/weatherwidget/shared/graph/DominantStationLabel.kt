package com.weatherwidget.shared.graph

import com.weatherwidget.shared.actuals.BlendContribution
import com.weatherwidget.shared.util.TempUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 *   Then `@` and the clock time that reading was taken — the same `lastReadingMs` the Blend tab shows
 *   in its "last read" column. Without it the number is undated, and a station that stopped reporting
 *   an hour ago looks exactly like one reporting now.
 * - **Visibility**: gated by [MAX_HOURS_SPAN] and by there being room. Zoomed-out views are excluded
 *   (see the constant), and a plot with no clear band simply gets no label — this is context, never
 *   worth pushing another number off the graph for. Also gated on there being a real thermometer to
 *   name: see [format]'s [BlendContribution] overload.
 * - **Position**: empty space, via [GraphEmptySpaceFinder], preferring the plot's edges.
 *
 * Each platform supplies its own measured metrics and a sampler of the *visible* curve, so this stays
 * free of android.graphics / Compose types.
 */
object DominantStationLabel {

    /**
     * Show the label for windows up to this span. 25 h admits the WIDE view (18 h) and every NARROW
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
     * Left-hugging first, then edge-first (the mirror of [ForecastDeltaLabel.X_FRACTIONS]). The
     * delta label is placed first and registers itself as an obstacle, so the two can never actually
     * overlap; opposite preferences just stop them landing shoulder-to-shoulder in the middle of an
     * otherwise empty plot. The leading 0.08f anchor is below the horizontal clamp for any realistic
     * label width, so on a clear plot the label resolves to its minimum 2dp inset against the left
     * edge instead of floating at 22% — the finder falls through to 0.22f/0.78f only when the left
     * band is blocked by the observed line.
     *
     * The trailing 0.92f is the right-edge mirror of that lead anchor, and like it clamps to the
     * minimum inset for any realistic width. It exists because the NOW indicator splits the plot: with
     * NOW two thirds across, 0.78f centres the label ON the line, and clearing it needs a box pushed
     * hard against the right edge. Deliberately last of the edge anchors — hugging an edge is a
     * fallback, not a preference — but ahead of the interior anchors, which on a NOW-split plot are
     * all worse.
     */
    val X_FRACTIONS = listOf(0.08f, 0.22f, 0.78f, 0.92f, 0.35f, 0.65f, 0.5f)

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
     * The visual parts of the label, so renderers can size each run independently: the station id
     * plus the `@`/am-pm punctuation stay small, the clock digits are mid-size, and the temperature
     * is largest.
     */
    enum class Part { STATION, TEMPERATURE, AT, TIME, AMPM }

    /** One run of the label with a single part role, e.g. `knuq ` / `66.2°` / ` @` / ` 8:10` / ` pm`. */
    data class Segment(val text: String, val part: Part)

    /**
     * The formatted label as segments plus their concatenation. [fullText] matches [format]; the
     * segments preserve the structure (and the position of the temperature) for mixed-size drawing.
     */
    data class LabelText(val fullText: String, val segments: List<Segment>)

    /**
     * The app's established time-of-day pattern (Observations, the fetch-failure indicator, the
     * forecast-evolution view), split into clock digits and the am/pm marker so renderers can keep
     * the punctuation (`@` and am/pm) small while the digits stay mid-size. The am/pm is lowercased
     * to match the lowercased callsign — at 9sp a shouted "PM" pulls more attention than the
     * temperature it qualifies.
     */
    private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
    private val AM_PM: DateTimeFormatter = DateTimeFormatter.ofPattern("a", Locale.getDefault())

    /** The clock digits and the am/pm suffix, formatted separately for independent sizing. */
    private data class ReadingTime(val clock: String, val amPm: String)

    /**
     * The production entry point: the dominant [contribution] as a label, or null when there is nothing
     * honest to say.
     *
     * Returns null for a synthetic backfill row ([BlendContribution.isSynthetic]). Those are not
     * thermometers — they are the source's own hourly forecast re-filed as observations — so naming one
     * would print an internal identifier (`open_meteo_main 71.2°`) beside a number that is a forecast,
     * which is the exact misattribution the raw-vs-resolved rule above exists to prevent. This is not a
     * rare case: forecast-only sources have no real stations, so under every source but NWS the synthetic
     * row is the only candidate and therefore always dominant. Matches the policy
     * `StationDailyExtremes.stationDailyExtreme` already applies when it names a station.
     */
    fun format(
        contribution: BlendContribution?,
        useCelsius: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String? = formatLabelText(contribution, useCelsius, zoneId)?.fullText

    /**
     * Structured form of [format] for renderers that style the temperature differently from the
     * station id and reading time. Returns null for the same reasons as [format] — synthetic backfill
     * rows and missing station/temperature.
     */
    fun formatLabelText(
        contribution: BlendContribution?,
        useCelsius: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): LabelText? {
        if (contribution == null || contribution.isSynthetic) return null
        return formatLabelText(
            stationId = contribution.stationId,
            rawTemp = contribution.rawTemp,
            useCelsius = useCelsius,
            lastReadingMs = contribution.lastReadingMs,
            zoneId = zoneId,
        )
    }

    /**
     * `knuq 73.4° @ 5:15 pm`. Lowercase because at this font size an all-caps callsign shouts louder
     * than the temperatures it sits among.
     *
     * The string rules only. Callers holding a blend row want the [BlendContribution] overload, which
     * additionally decides whether the row is a station worth naming.
     *
     * [lastReadingMs] is when that station took the reading. Null (or out of range) drops just the
     * `@ …` clause rather than the whole label — an undated temperature is still worth more than
     * nothing. Returns null only when there is no station or no temperature to name.
     */
    fun format(
        stationId: String?,
        rawTemp: Float?,
        useCelsius: Boolean,
        lastReadingMs: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String? = formatLabelText(stationId, rawTemp, useCelsius, lastReadingMs, zoneId)?.fullText

    /**
     * Builds the label as distinct segments — station id, temperature, `@`, clock time, and am/pm —
     * so a renderer can size each run independently. The concatenation ([LabelText.fullText]) is
     * exactly what [format] returns.
     */
    fun formatLabelText(
        stationId: String?,
        rawTemp: Float?,
        useCelsius: Boolean,
        lastReadingMs: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): LabelText? {
        val id = stationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val temp = TempUtils.formatTemp(rawTemp, useCelsius) ?: return null
        val reading = lastReadingMs?.takeIf { it > 0L }?.let { ms ->
            runCatching {
                val zoned = Instant.ofEpochMilli(ms).atZone(zoneId)
                ReadingTime(
                    clock = zoned.format(CLOCK_TIME),
                    amPm = zoned.format(AM_PM).lowercase(Locale.getDefault()),
                )
            }.getOrNull()
        }
        val segments = buildList {
            add(Segment("${id.lowercase()} ", Part.STATION))
            add(Segment(temp, Part.TEMPERATURE))
            if (reading != null) {
                add(Segment(" @", Part.AT))
                add(Segment(" ${reading.clock}", Part.TIME))
                add(Segment(" ${reading.amPm}", Part.AMPM))
            }
        }
        return LabelText(
            fullText = segments.joinToString(separator = "") { it.text },
            segments = segments,
        )
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
     * all sides. [vetoBounds] are draw-over-nothing obstacles that exert no repulsion — the NOW
     * indicator line; see [GraphEmptySpaceFinder.find].
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
        vetoBounds: List<GraphRect> = emptyList(),
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
                vetoBounds = vetoBounds,
            ) ?: return null

        return Placement(
            text = text,
            centerX = slot.centerX,
            baselineY = slot.baselineY,
            box = slot.box,
        )
    }
}
