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
        /** ABOVE_HIGH, ABOVE_RAIN, or NIGHT_SHIFTED_LEFT / NIGHT_SHIFTED_SCALED / NIGHT_CENTERED. */
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
        /** dp→px for the gaps. */
        val pxPerDp: Float,
    )

    /**
     * The day chance sits on the high label (bottom = high top − [DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP],
     * a slight tuck), clamped to the ceiling. The peak hour sits on the chance label the same way. It
     * is dropped when it would cross the ceiling; the chance label never moves to make room for it.
     */
    fun layoutDay(host: Host, input: DayInput, platform: String): DayLabels? {
        val anchorTop = DailyRainLabelPlanner.resolveDayRainAnchorTop(input.highLabelTop, input.snapshotBarTop)
        if (anchorTop == null) {
            Log.d(TAG, "dayRainLabel skipped: platform=$platform date=${input.date} reason=no_high_label")
            return null
        }
        val fontScale = DailyRainLabels.rainLabelFontScale(input.isPast, input.probability, input.daysFromToday)
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
            if (peakTop < input.ceiling) {
                Log.d(
                    TAG,
                    "rainPeakLabel skipped: platform=$platform date=${input.date} text=\"$peakText\"" +
                        " reason=ceiling top=${peakTop.r()} ceiling=${input.ceiling.r()}",
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
        /** This day's drawn low label. */
        val leftLowLabel: DailyRainLabelPlanner.Box,
        /** The next day's drawn low label; null when there is none. */
        val rightLowLabel: DailyRainLabelPlanner.Box?,
        /** Both neighbours' icons and low labels ([DailyRainLabelPlanner.lowLabelObstacles]). */
        val obstacles: List<DailyRainLabelPlanner.Box>,
        /** Top of the day-name row; the label never reaches into it. */
        val dayLabelTop: Float,
        val canvasWidth: Float,
        /** dp→px for gaps, nudges and margins. */
        val pxPerDp: Float,
    )

    /**
     * The night chance starts centred in the gap, tucked under the higher of the two low labels, and
     * nudges left, right or down around the neighbours' icons and low labels
     * ([DailyRainLabelPlanner.placeNightLabel]). It stays *between* the two lows: its bottom never goes
     * below the lower low label's bottom, so a slight overlap wins over sinking under both. When the
     * lows sit on the day-name row (the coldest days), there is no room under them, so it rises until it
     * clears the row — level with the lows, in the gap between them.
     */
    fun layoutNight(host: Host, input: NightInput, platform: String): Placed {
        val p = input.pxPerDp
        val right = input.rightLowLabel
        val anchorBottom = if (right != null) minOf(input.leftLowLabel.bottom, right.bottom) else input.leftLowLabel.bottom
        val lowerLowBottom = if (right != null) maxOf(input.leftLowLabel.bottom, right.bottom) else input.leftLowLabel.bottom
        val roomBelowDp = (input.dayLabelTop - anchorBottom).coerceAtLeast(0f) / p
        val tuck = DailyRainLabelPlanner.calculateNightTuck(roomBelowDp)
        val tuckTop = anchorBottom - tuck.dynamicOverlapDp * p + tuck.roomyDownDp * p

        // Centred in the gap at full size; else at the reduced size; else centred on the column.
        val edge = DailyRainLabels.RAIN_LABEL_EDGE_MARGIN_DP * p
        fun fitsAt(cx: Float, width: Float) = cx - width / 2f >= edge && cx + width / 2f <= input.canvasWidth - edge
        val baseScale = DailyRainLabels.rainLabelFontScale(input.isPast, input.probability, input.daysFromToday) *
            DailyRainLabels.NIGHT_SCALE
        var fontScale = baseScale
        var size = host.measure(input.text, Kind.NIGHT, fontScale)
        var homeCenterX = input.gapCenterX
        var placement = "NIGHT_SHIFTED_LEFT"
        if (!fitsAt(homeCenterX, size.width)) {
            val reducedScale = baseScale * NIGHT_REDUCED_SCALE
            val reduced = host.measure(input.text, Kind.NIGHT, reducedScale)
            if (fitsAt(homeCenterX, reduced.width)) {
                fontScale = reducedScale
                size = reduced
                placement = "NIGHT_SHIFTED_SCALED"
            } else {
                val half = size.width / 2f
                homeCenterX = input.columnCenterX.coerceIn(edge + half, (input.canvasWidth - edge - half).coerceAtLeast(edge + half))
                placement = "NIGHT_CENTERED"
            }
        }

        val homeTop = minOf(tuckTop, input.dayLabelTop - size.height)
        // Between the two lows: never below the lower one's bottom (home itself is always allowed).
        val maxBottom = minOf(input.dayLabelTop, maxOf(lowerLowBottom, homeTop + size.height))
        val placed = DailyRainLabelPlanner.placeNightLabel(
            homeCenterX = homeCenterX,
            homeTop = homeTop,
            width = size.width,
            height = size.height,
            obstacles = input.obstacles,
            maxShiftPx = input.columnWidth * DailyRainLabels.NIGHT_NUDGE_MAX_SHIFT_FRACTION,
            maxBottom = maxBottom,
            minLeft = edge,
            maxRight = input.canvasWidth - edge,
            density = p,
        )
        Log.v(
            TAG,
            "nightRainLabel position: platform=$platform date=${input.date} text=\"${input.text}\" fit=$placement" +
                " anchorBottom=${anchorBottom.r()} lowerLowBottom=${lowerLowBottom.r()} roomBelowDp=${roomBelowDp.r()}" +
                " tuckTop=${tuckTop.r()} home=(${homeCenterX.r()},${homeTop.r()}) size=${size.width.r()}x${size.height.r()}" +
                " resolved=(${placed.centerX.r()},${placed.top.r()}) placement=${placed.placement}" +
                " offsetDp=(${(placed.dxPx / p).r()},${(placed.dyPx / p).r()}) overlapDp=${(placed.overlapPx / p).r()}" +
                " maxBottom=${maxBottom.r()} dayLabelTop=${input.dayLabelTop.r()}" +
                " obstacles=${input.obstacles.joinToString(";", "[", "]") { "${it.left.r()},${it.top.r()},${it.right.r()},${it.bottom.r()}" }}",
        )
        return Placed(
            Kind.NIGHT, input.text, fontScale, placed.centerX, placed.top, size.width, size.height,
            placed.top + size.baseline, placement, anchorBottom,
        )
    }

    private fun Float.r(): String = "%.1f".format(this)
}
