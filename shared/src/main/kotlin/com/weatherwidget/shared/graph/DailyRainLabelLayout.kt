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
     * ([DailyRainLabelPlanner.placeNightLabel]), preferring down when there is lots of room below, then
     * eases off anything it hugs ([DailyRainLabelPlanner.easeOffNeighbors]). It may sink below both
     * lows, down to the day-name row, when nothing above is clear; a label that does slides right off the left low
     * ([DailyRainLabelPlanner.slideRightAfterSinking]). When the lows sit on the day-name row (the coldest days), there is no room under them, so it rises until it
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
        val maxBottom = input.dayLabelTop
        val roomyBelow = input.dayLabelTop - (homeTop + size.height) >= DailyRainLabels.NIGHT_ROOMY_BELOW_DP * p
        val maxShiftPx = input.columnWidth * DailyRainLabels.NIGHT_NUDGE_MAX_SHIFT_FRACTION
        val placed = DailyRainLabelPlanner.placeNightLabel(
            homeCenterX = homeCenterX,
            homeTop = homeTop,
            width = size.width,
            height = size.height,
            obstacles = input.obstacles,
            maxShiftPx = maxShiftPx,
            maxBottom = maxBottom,
            minLeft = edge,
            maxRight = input.canvasWidth - edge,
            density = p,
            sidewaysCost = if (roomyBelow) DailyRainLabels.NIGHT_ROOMY_SIDEWAYS_COST else 1f,
        )
        val sunk = placed.dyPx > 0f && placed.top + size.height > lowerLowBottom
        val placedCenterX = if (sunk) {
            DailyRainLabelPlanner.slideRightAfterSinking(
                centerX = placed.centerX,
                top = placed.top,
                width = size.width,
                height = size.height,
                leftLowLabel = input.leftLowLabel,
                obstacles = input.obstacles,
                maxRight = input.canvasWidth - edge,
                density = p,
            )
        } else {
            placed.centerX
        }
        val eased = DailyRainLabelPlanner.easeOffNeighbors(
            homeCenterX = homeCenterX,
            centerX = placedCenterX,
            top = placed.top,
            width = size.width,
            height = size.height,
            obstacles = input.obstacles,
            maxShiftPx = maxShiftPx,
            rightLowLabel = input.rightLowLabel,
            maxBottom = maxBottom,
            minLeft = edge,
            density = p,
            searchMovedLeft = placed.dxPx < 0f,
        )
        val final = if (placed.dxPx != 0f) {
            DailyRainLabelPlanner.recenterOverBlankLineBox(
                homeCenterX = homeCenterX,
                centerX = eased.centerX,
                top = eased.top,
                width = size.width,
                height = size.height,
                obstacles = input.obstacles,
                maxBottom = maxBottom,
                density = p,
            )
        } else {
            eased
        }
        val offInkTop = DailyRainLabelPlanner.dropOffDigitInk(
            centerX = final.centerX,
            top = final.top,
            width = size.width,
            height = size.height,
            obstacles = input.obstacles,
            maxBottom = maxBottom,
            density = p,
        )
        val finalCenterX = DailyRainLabelPlanner.slideOffDigitInk(
            centerX = final.centerX,
            top = offInkTop,
            width = size.width,
            height = size.height,
            leftLowLabel = input.leftLowLabel,
            rightLowLabel = input.rightLowLabel,
            obstacles = input.obstacles,
            minCenterX = homeCenterX - maxShiftPx,
            maxCenterX = homeCenterX + maxShiftPx,
            density = p,
        )
        val finalTop = DailyRainLabelPlanner.dropClearOfDegree(
            centerX = finalCenterX,
            top = offInkTop,
            width = size.width,
            height = size.height,
            obstacles = input.obstacles,
            maxBottom = maxBottom,
            density = p,
        )
        val placedCenterXFinal = DailyRainLabelPlanner.slideOffDegree(
            centerX = finalCenterX,
            top = finalTop,
            width = size.width,
            height = size.height,
            obstacles = input.obstacles,
            maxCenterX = homeCenterX + maxShiftPx,
            density = p,
        )
        val floorEased = DailyRainLabelPlanner.easeOffLeftLowAtFloor(
            homeCenterX = homeCenterX,
            centerX = placedCenterXFinal,
            top = finalTop,
            width = size.width,
            height = size.height,
            leftLowLabel = input.leftLowLabel,
            obstacles = input.obstacles,
            maxBottom = maxBottom,
            density = p,
        )
        val floorTop = DailyRainLabelPlanner.dropOffDegreeAtFloor(
            centerX = floorEased.centerX,
            top = floorEased.top,
            width = size.width,
            height = size.height,
            blankBelowPx = size.height - size.baseline,
            obstacles = input.obstacles,
            maxBottom = maxBottom,
            density = p,
        )
        // Centred under its own low (the last column, or a label too wide for its gap): tuck up to
        // 1 dp under that low's digit ink. The gap tuck measures from the line box, whose bottom
        // 22 % is blank, and adds roomy drops (Pixel 7 Pro Sat "100%" sat 6 dp under "57°").
        val tuckedTop = if (placement == "NIGHT_CENTERED") {
            tuckUnderOwnLow(input, floorEased.centerX, floorTop, size.width, size.height)
        } else {
            floorTop
        }
        Log.v(
            TAG,
            "nightRainLabel position: platform=$platform date=${input.date} text=\"${input.text}\" fit=$placement" +
                " anchorBottom=${anchorBottom.r()} lowerLowBottom=${lowerLowBottom.r()} roomBelowDp=${roomBelowDp.r()}" +
                " tuckTop=${tuckTop.r()} home=(${homeCenterX.r()},${homeTop.r()}) size=${size.width.r()}x${size.height.r()}" +
                " resolved=(${floorEased.centerX.r()},${tuckedTop.r()}) ownLowTuckDp=${((floorTop - tuckedTop) / p).r()} placement=${placed.placement} sunk=$sunk" +
                " recentered=${final != eased} inkDropDp=${((offInkTop - final.top) / p).r()} inkSlideDp=${((finalCenterX - final.centerX) / p).r()} degreeDropDp=${((finalTop - offInkTop) / p).r()} degreeSlideDp=${((placedCenterXFinal - finalCenterX) / p).r()} floorEaseDp=(${((floorEased.centerX - placedCenterXFinal) / p).r()},${((floorEased.top - finalTop) / p).r()}) floorDegreeDropDp=${((floorTop - floorEased.top) / p).r()}" +
                " easedDp=(${((eased.centerX - placedCenterX) / p).r()},${((eased.top - placed.top) / p).r()})" +
                " offsetDp=(${(placed.dxPx / p).r()},${(placed.dyPx / p).r()}) overlapDp=${(placed.overlapPx / p).r()}" +
                " maxBottom=${maxBottom.r()} dayLabelTop=${input.dayLabelTop.r()}" +
                " obstacles=${input.obstacles.joinToString(";", "[", "]") { "${it.left.r()},${it.top.r()},${it.right.r()},${it.bottom.r()}" }}",
        )
        return Placed(
            Kind.NIGHT, input.text, fontScale, floorEased.centerX, tuckedTop, size.width, size.height,
            tuckedTop + size.baseline, placement, anchorBottom,
        )
    }

    /**
     * Raises a label centred under its own day's low to [NIGHT_LABEL_GAP_DP] below that low's digit
     * ink, stopping short of any other obstacle's ink. Never moves it down.
     */
    private fun tuckUnderOwnLow(input: NightInput, centerX: Float, top: Float, width: Float, height: Float): Float {
        val p = input.pxPerDp
        val gap = NIGHT_LABEL_GAP_DP * p
        val low = input.leftLowLabel
        val digits = input.obstacles.firstOrNull { it.isLowDigits && it.left == low.left } ?: low
        val inkBottom = digits.bottom - (digits.bottom - digits.top) * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION
        val target = inkBottom + gap
        if (target >= top) return top
        val from = Placed(Kind.NIGHT, "", 1f, centerX, top, width, height, top, "", 0f)
        val ink = DailyRainLabelPlanner.inkBoxes(input.obstacles)
        val step = (0.5f * p).coerceAtLeast(0.5f)
        var placedTop = top
        while (placedTop > target) {
            val next = maxOf(placedTop - step, target)
            if (hitsInk(from.copy(top = next), ink, gap, from)) break
            placedTop = next
        }
        return placedTop
    }

    /** A laid-out night label with the input it was placed from (its obstacles and limits). */
    data class LaidOutNight(val input: NightInput, val label: Placed)

    /**
     * Keeps neighbouring night labels from overlapping, after every one is laid out and before any
     * is drawn. [layoutNight] places each label against its two columns' icons and low labels only,
     * so a wide one (Fri ".24in") and the last column's label, which is centred on its own column
     * (Sat "100%"), could land on each other (emulator 2026-11-06).
     *
     * For each pair, in column order, that comes within [NIGHT_LABEL_GAP_DP] of each other, the
     * right label slides right (to within [NIGHT_SEPARATION_EDGE_MARGIN_DP] of the edge), then
     * rises, and only then does the left label drop by what is still needed (user: nudge it right,
     * raise it, and if that isn't enough lower the amount). Each move stops short of its own
     * obstacles' ink and the day-name row. A pair that doesn't overlap is left alone. Labels are in column order; the result keeps
     * that order (plans/261106-night-rain-labels-overlap-each-other.md).
     */
    fun separateNightLabels(labels: List<LaidOutNight>, platform: String): List<Placed> {
        val out = labels.map { it.label }.toMutableList()
        for (i in 1 until labels.size) {
            val leftIn = labels[i - 1].input
            val rightIn = labels[i].input
            val p = rightIn.pxPerDp
            val gap = NIGHT_LABEL_GAP_DP * p
            val step = (0.5f * p).coerceAtLeast(0.5f)
            var l = out[i - 1]
            var r = out[i]
            if (!near(l, r, gap)) continue
            val startL = l
            val startR = r

            // 1. Right, until clear of the left label sideways. A label pushed by its neighbour may
            // use most of the edge margin: the last column's label already sat on it (emulator Sat).
            val maxRight = rightIn.canvasWidth - NIGHT_SEPARATION_EDGE_MARGIN_DP * p
            val rightInk = DailyRainLabelPlanner.inkBoxes(rightIn.obstacles)
            val wantRight = l.right + gap - r.left
            var dx = 0f
            while (dx < wantRight) {
                val next = minOf(dx + step, wantRight)
                val moved = r.copy(centerX = startR.centerX + next)
                if (moved.right > maxRight || hitsInk(moved, rightInk, gap, startR)) break
                dx = next
            }
            r = r.copy(centerX = startR.centerX + dx)

            // 2. Up, until its bottom clears the left label's top.
            if (near(l, r, gap)) {
                val wantUp = r.bottom + gap - l.top
                var dy = 0f
                while (dy < wantUp) {
                    val next = minOf(dy + step, wantUp)
                    val moved = r.copy(top = r.top - next, baseline = r.baseline - next)
                    if (moved.top < 0f || hitsInk(moved, rightInk, gap, r)) break
                    dy = next
                }
                r = r.copy(top = r.top - dy, baseline = r.baseline - dy)
            }

            // 3. The left label down by whatever overlap is left, above the day-name row.
            if (near(l, r, gap)) {
                val leftInk = DailyRainLabelPlanner.inkBoxes(leftIn.obstacles)
                val wantDown = r.bottom + gap - l.top
                var dy = 0f
                while (dy < wantDown) {
                    val next = minOf(dy + step, wantDown)
                    val moved = l.copy(top = l.top + next, baseline = l.baseline + next)
                    if (moved.bottom > leftIn.dayLabelTop || hitsInk(moved, leftInk, gap, l)) break
                    dy = next
                }
                l = l.copy(top = l.top + dy, baseline = l.baseline + dy)
            }

            out[i - 1] = l
            out[i] = r
            Log.v(
                TAG,
                "nightLabelSeparation: platform=$platform left=${leftIn.date} \"${l.text}\" right=${rightIn.date} \"${r.text}\"" +
                    " rightDp=${((r.centerX - startR.centerX) / p).r()} raiseDp=${((startR.top - r.top) / p).r()}" +
                    " lowerLeftDp=${((l.top - startL.top) / p).r()} clear=${!near(l, r, gap)}" +
                    " left=(${l.left.r()},${l.top.r()},${l.right.r()},${l.bottom.r()})" +
                    " right=(${r.left.r()},${r.top.r()},${r.right.r()},${r.bottom.r()})",
            )
        }
        return out
    }

    /** Space kept between two night labels, and between a moved label and an obstacle's ink. */
    const val NIGHT_LABEL_GAP_DP = 1f

    /**
     * Edge margin for a night label slid right by [separateNightLabels]; every other label keeps
     * [DailyRainLabels.RAIN_LABEL_EDGE_MARGIN_DP].
     */
    const val NIGHT_SEPARATION_EDGE_MARGIN_DP = 1f

    private fun near(a: Placed, b: Placed, gap: Float) =
        a.left < b.right + gap && b.left < a.right + gap && a.top < b.bottom + gap && b.top < a.bottom + gap

    /**
     * True when [moved] reaches further into any ink box, grown by [gap] above and below, than it
     * did at [from]: a label may stay where it started but never move deeper into ink.
     */
    private fun hitsInk(moved: Placed, ink: List<DailyRainLabelPlanner.Box>, gap: Float, from: Placed) = ink.any {
        fun area(x: Placed): Float {
            val w = minOf(x.right, it.right) - maxOf(x.left, it.left)
            val h = minOf(x.bottom, it.bottom + gap) - maxOf(x.top, it.top - gap)
            return if (w > 0f && h > 0f) w * h else 0f
        }
        area(moved) > area(from) + 0.01f
    }

    private fun Float.r(): String = "%.1f".format(this)
}
