package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.Log

/**
 * Lays out the daily graph's rain labels for the Android widget and desktop: the day chance above
 * the high label, its peak hour above that, and the night chance between two columns. This runner
 * owns the whole rule — font scale, anchors, gaps, ceiling, edge fit, collision search, bottom limit
 * and the trace. Each platform supplies a [Host] that measures text, and draws the boxes it gets back
 * (plans/261029-shared-daily-rain-labels.md).
 */
object DailyRainLabelLayout {
    private const val TAG = "DailyRainLabelLayout"

    /** Shrink applied to a night label that does not fit centred in the gap at full size. */
    const val NIGHT_REDUCED_SCALE = 0.85f

    enum class Kind { DAY, PEAK, NIGHT }

    /** A measured label: [height] from ink-box top to bottom, [baseline] measured down from the top. */
    data class TextSize(val width: Float, val height: Float, val baseline: Float)

    fun interface Host {
        /**
         * Measures [text] at the platform's base size for [kind] times [fontScale]. DAY and PEAK share
         * the day-rain base size; NIGHT has its own base. [fontScale] already includes the
         * probability/distance scale, [DailyRainLabels.NIGHT_SCALE] and
         * [DailyRainLabels.RAIN_PEAK_FONT_SCALE].
         */
        fun measure(text: String, kind: Kind, fontScale: Float): TextSize

        /**
         * The drawn ink of [text], relative to the top-left of the box [measure] returns. Null when the
         * host cannot tell: the placer then takes the full width from the box top to the baseline.
         */
        fun ink(text: String, kind: Kind, fontScale: Float): DailyRainLabelPlanner.Box? = null

        /** The same ink as row bands ([InkScan.rowBands]), relative to the box's top-left; null = [ink] alone. */
        fun inkBands(text: String, kind: Kind, fontScale: Float): List<DailyRainLabelPlanner.Box>? = null
    }

    data class Placed(
        val kind: Kind,
        val text: String,
        /** Font size relative to the kind's base size; draw with exactly this. */
        val fontScale: Float,
        val centerX: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val baseline: Float,
        /** ABOVE_HIGH, ABOVE_RAIN, or NIGHT_SHIFTED_LEFT / NIGHT_SHIFTED_SCALED / NIGHT_AT_EDGE. */
        val placement: String,
        /** What the label hangs from: the high label's top, the chance label's top, or the night tuck line. */
        val anchorY: Float,
    ) {
        val left: Float get() = centerX - width / 2f
        val right: Float get() = centerX + width / 2f
        val bottom: Float get() = top + height
    }

    data class DayLabels(
        val rain: Placed,
        /** Null when there is no peak text or the peak would cross the ceiling. */
        val peak: Placed?,
        /**
         * Highest point the chance label and its peak would reach before the ceiling clamp, for
         * [DailyTopStack] headroom.
         */
        val rawTop: Float,
    )

    data class DayInput(
        val date: String,
        val rainText: String,
        val peakText: String?,
        val probability: Int?,
        val isPast: Boolean,
        val daysFromToday: Int,
        val centerX: Float,
        /** Top of the high label as drawn (the topmost one when two are drawn); null when none is drawn. */
        val highLabelTop: Float?,
        /** Top of the triple bar's left slot, when drawn; the chance label clears it too. */
        val snapshotBarTop: Float?,
        /** The high label's top before any ceiling clamp, for [DayLabels.rawTop]. */
        val rawHighLabelTop: Float? = highLabelTop,
        /** Highest Y a label may use: the [DailyTopStack] ceiling, or the canvas top if that is lower. */
        val ceiling: Float,
        /**
         * Highest Y the peak hour may use. It may overlap the header (user, 2026-11-04), so platforms
         * pass the top of what they can draw: Android the bitmap top, desktop the header row's top.
         * The chance label below it still keeps to [ceiling].
         */
        val peakCeiling: Float = ceiling,
        /** dp→px for the gaps. */
        val pxPerDp: Float,
    )

    /**
     * The day chance sits on the high label (bottom = high top − [DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP],
     * a slight tuck), clamped to the ceiling. The peak hour sits on the chance label the same way and
     * may rise into the header; it is dropped only when it would leave the drawable area
     * ([DayInput.peakCeiling]). The chance label never moves to make room for it.
     */
    fun layoutDay(host: Host, input: DayInput, platform: String): DayLabels? {
        val anchorTop = DailyRainLabelPlanner.resolveDayRainAnchorTop(input.highLabelTop, input.snapshotBarTop)
        if (anchorTop == null) {
            Log.d(TAG, "dayRainLabel skipped: platform=$platform date=${input.date} reason=no_high_label")
            return null
        }
        val fontScale = DailyRainLabels.rainLabelFontScale(input.isPast, input.probability, input.daysFromToday) *
            DailyRainLabels.dayLabelKindFontScale(input.rainText)
        val size = host.measure(input.rainText, Kind.DAY, fontScale)
        val gapPx = DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP * input.pxPerDp
        val unclampedTop = DailyRainLabelPlanner.resolveRainAboveAnchorTop(anchorTop, size.height, gapPx)
        val top = unclampedTop.coerceAtLeast(input.ceiling)
        val rawAnchorTop = DailyRainLabelPlanner.resolveDayRainAnchorTop(input.rawHighLabelTop, input.snapshotBarTop) ?: anchorTop
        val rawTop = DailyRainLabelPlanner.resolveRainAboveAnchorTop(rawAnchorTop, size.height, gapPx)
        val rain = Placed(
            Kind.DAY, input.rainText, fontScale, input.centerX, top, size.width, size.height,
            top + size.baseline, "ABOVE_HIGH", anchorTop,
        )
        if (top > unclampedTop) {
            Log.d(
                TAG,
                "dayRainLabel clamped to ceiling: platform=$platform date=${input.date} text=\"${input.rainText}\"" +
                    " top=${unclampedTop.r()} ceiling=${input.ceiling.r()} anchorTop=${anchorTop.r()}",
            )
        }

        var stackRawTop = rawTop
        val peak = input.peakText?.let { peakText ->
            val peakScale = fontScale * DailyRainLabels.RAIN_PEAK_FONT_SCALE
            val peakSize = host.measure(peakText, Kind.PEAK, peakScale)
            val peakGapPx = DailyRainLabels.RAIN_PEAK_GAP_DP * input.pxPerDp
            stackRawTop = DailyRainLabelPlanner.resolveRainAboveAnchorTop(rawTop, peakSize.height, peakGapPx)
            val peakTop = DailyRainLabelPlanner.resolveRainAboveAnchorTop(top, peakSize.height, peakGapPx)
            if (peakTop < input.peakCeiling) {
                Log.d(
                    TAG,
                    "rainPeakLabel skipped: platform=$platform date=${input.date} text=\"$peakText\"" +
                        " reason=ceiling top=${peakTop.r()} peakCeiling=${input.peakCeiling.r()}" +
                        " headerCeiling=${input.ceiling.r()}",
                )
                null
            } else {
                Placed(
                    Kind.PEAK, peakText, peakScale, input.centerX, peakTop, peakSize.width, peakSize.height,
                    peakTop + peakSize.baseline, "ABOVE_RAIN", top,
                )
            }
        }
        Log.v(
            TAG,
            "dayRainLabel placement: platform=$platform date=${input.date} text=\"${input.rainText}\"" +
                " anchorTop=${anchorTop.r()} highTop=${input.highLabelTop?.r()} snapshotTop=${input.snapshotBarTop?.r()}" +
                " top=${top.r()} bottom=${rain.bottom.r()} rawTop=${rawTop.r()} ceiling=${input.ceiling.r()}" +
                " peak=${peak?.let { "\"${it.text}\"@${it.top.r()}" }}",
        )
        return DayLabels(rain, peak, stackRawTop)
    }

    data class NightInput(
        val date: String,
        val text: String,
        val probability: Int?,
        val isPast: Boolean,
        val daysFromToday: Int,
        val columnCenterX: Float,
        /** Midpoint between this column and the next (or the column's right edge for the last one). */
        val gapCenterX: Float,
        val columnWidth: Float,
        /** Ink of this day's low label (digits and °). */
        val leftLowInk: DailyRainLabelPlanner.Box,
        /** Ink of the next day's low label; null when there is none. */
        val rightLowInk: DailyRainLabelPlanner.Box?,
        /**
         * Measured ink of both neighbours' icons, low labels and day names, as row bands
         * ([InkScan.rowBands]) so open glyph space (under a °, a 4's crossbar) stays free.
         */
        val obstacles: List<DailyRainLabelPlanner.Box>,
        /**
         * Top of the day names' ink. The label's ink stays above it, except by up to
         * [NightRainLabelPlacer.Weights.floorAllowanceDp] between names; [obstacles] carry the names' ink.
         */
        val floorInkTop: Float,
        val canvasWidth: Float,
        /** dp→px for gaps, steps and margins. */
        val pxPerDp: Float,
    )

    /**
     * Lays out every night label of the graph in column order. Each label is placed by
     * [NightRainLabelPlacer] against its two columns' ink and the ink of the labels already placed,
     * so neighbouring night labels never overlap.
     */
    fun layoutNights(
        host: Host,
        inputs: List<NightInput>,
        platform: String,
        weights: NightRainLabelPlacer.Weights = NightRainLabelPlacer.DEFAULT_WEIGHTS,
    ): List<Placed> {
        val placedInk = mutableListOf<DailyRainLabelPlanner.Box>()
        return inputs.map { input ->
            val (label, ink) = layoutNight(host, input, platform, placedInk.toList(), weights)
            placedInk += ink
            label
        }
    }

    /**
     * One night label: centred in the gap between its column and the next at full size, else at
     * [NIGHT_REDUCED_SCALE], else pushed as far toward that gap as the canvas edge margin allows
     * ("NIGHT_AT_EDGE": the last column, whose gap is the canvas edge). The night belongs after its
     * day, so the last one slides right rather than centring under its own low, where it crowded the
     * previous night's label (user, 2026-11-18). Within that mode [NightRainLabelPlacer] scores every
     * spot; the reduced size is used only when full size has no clear spot.
     * Returns the label and its ink.
     */
    private fun layoutNight(
        host: Host,
        input: NightInput,
        platform: String,
        placedLabels: List<DailyRainLabelPlanner.Box>,
        weights: NightRainLabelPlacer.Weights,
    ): Pair<Placed, DailyRainLabelPlanner.Box> {
        val p = input.pxPerDp
        val edge = DailyRainLabels.RAIN_LABEL_EDGE_MARGIN_DP * p
        val baseScale = DailyRainLabels.rainLabelFontScale(input.isPast, input.probability, input.daysFromToday) *
            DailyRainLabels.NIGHT_SCALE * DailyRainLabels.traceFontScale(input.text)
        val reducedScale = baseScale * NIGHT_REDUCED_SCALE
        fun fitsAt(cx: Float, width: Float) = cx - width / 2f >= edge && cx + width / 2f <= input.canvasWidth - edge
        val obstacles = input.obstacles + placedLabels
        val clearance = weights.clearanceDp * p

        fun attempt(scale: Float, placement: String): Attempt {
            val size = host.measure(input.text, Kind.NIGHT, scale)
            val ink = host.ink(input.text, Kind.NIGHT, scale) ?: DailyRainLabelPlanner.Box(0f, 0f, size.width, size.baseline)
            val centred = placement == "NIGHT_AT_EDGE"
            val target = if (centred) {
                val half = size.width / 2f
                input.gapCenterX.coerceIn(edge + half, (input.canvasWidth - edge - half).coerceAtLeast(edge + half))
            } else {
                input.gapCenterX
            }
            val right = input.rightLowInk.takeUnless { centred }
            // Preferred row: [rowGapDp] under the higher low's ink; it may rise to [clearanceDp] under it.
            val anchorInkBottom = minOf(input.leftLowInk.bottom, right?.bottom ?: Float.MAX_VALUE)
            val tuckTop = anchorInkBottom + clearance - ink.top
            val preferredTop = anchorInkBottom + weights.rowGapDp * p - ink.top
            val result = NightRainLabelPlacer.place(
                width = size.width,
                ink = ink,
                inkBands = host.inkBands(input.text, Kind.NIGHT, scale)?.takeIf { it.isNotEmpty() } ?: listOf(ink),
                targetCenterX = target,
                preferredTop = preferredTop,
                minTop = tuckTop,
                maxInkBottom = input.floorInkTop + weights.floorAllowanceDp * p,
                minLeft = edge,
                maxRight = input.canvasWidth - edge,
                maxShift = input.columnWidth * DailyRainLabels.NIGHT_NUDGE_MAX_SHIFT_FRACTION,
                obstacles = obstacles,
                density = p,
                weights = weights,
            )
            return Attempt(scale, placement, size, ink, target, preferredTop, anchorInkBottom, result)
        }

        val chosen = when {
            fitsAt(input.gapCenterX, host.measure(input.text, Kind.NIGHT, baseScale).width) -> {
                // The smaller size only when full size has no clear spot (user's fallback, 2026-11-14):
                // shrinking to win a few score points squeezed ".51in" and "94%" beside icons (Fold 2026-11-16).
                val full = attempt(baseScale, "NIGHT_SHIFTED_LEFT")
                if (full.result.best.clear) {
                    full
                } else {
                    attempt(reducedScale, "NIGHT_SHIFTED_SCALED").takeIf { it.result.best.clear } ?: full
                }
            }
            fitsAt(input.gapCenterX, host.measure(input.text, Kind.NIGHT, reducedScale).width) ->
                attempt(reducedScale, "NIGHT_SHIFTED_SCALED")
            else -> attempt(baseScale, "NIGHT_AT_EDGE")
        }

        val best = chosen.result.best
        val size = chosen.size
        val label = Placed(
            Kind.NIGHT, input.text, chosen.scale, best.centerX, best.top, size.width, size.height,
            best.top + size.baseline, chosen.placement, chosen.anchorInkBottom,
        )
        val labelInk = DailyRainLabelPlanner.Box(
            label.left + chosen.ink.left, label.top + chosen.ink.top, label.left + chosen.ink.right, label.top + chosen.ink.bottom,
        )
        val runner = chosen.result.runnerUp
        Log.v(
            TAG,
            "nightRainLabel position: platform=$platform date=${input.date} text=\"${input.text}\" fit=${chosen.placement}" +
                " target=${chosen.target.r()} preferredTop=${chosen.preferredTop.r()} size=${size.width.r()}x${size.height.r()}" +
                " resolved=(${best.centerX.r()},${best.top.r()}) inkCentreOffDp=${(((labelInk.left + labelInk.right) / 2f - chosen.target) / p).r()}" +
                " belowPreferredDp=${((best.top - chosen.preferredTop) / p).r()} score=${best.score.r()}" +
                " (centre=${best.centreCost.r()} vertical=${best.verticalCost.r()} crowd=${best.crowdCost.r()} intrusion=${best.intrusionCost.r()})" +
                " clear=${best.clear} minGapDp=${(best.minGap / p).r()}" +
                " runnerUp=${runner?.let { "(${it.centerX.r()},${it.top.r()},${it.score.r()})" }}" +
                " ink=(${labelInk.left.r()},${labelInk.top.r()},${labelInk.right.r()},${labelInk.bottom.r()})" +
                " floorInkTop=${input.floorInkTop.r()}" +
                " obstacles=${obstacles.joinToString(";", "[", "]") { "${it.left.r()},${it.top.r()},${it.right.r()},${it.bottom.r()}" }}",
        )
        return label to labelInk
    }

    private data class Attempt(
        val scale: Float,
        val placement: String,
        val size: TextSize,
        val ink: DailyRainLabelPlanner.Box,
        val target: Float,
        val preferredTop: Float,
        val anchorInkBottom: Float,
        val result: NightRainLabelPlacer.Result,
    )

    private fun Float.r(): String = "%.1f".format(this)
}
