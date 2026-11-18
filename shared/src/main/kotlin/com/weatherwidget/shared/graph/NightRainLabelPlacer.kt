package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.DailyRainLabelPlanner.Box
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Places a night rain label by scoring every candidate spot on a [Weights.stepDp] grid against the
 * measured ink of the neighbouring icons, low labels and earlier night labels, and taking the lowest
 * score. Replaces the least-move search and its chain of corrective steps
 * (plans/261116-night-rain-label-scored-placement-redesign.md).
 *
 * Every rule is a term of one score, so a preference is a weight, not a step:
 * - centred between the columns (quadratic: a dp off is cheap, several are not);
 * - near the preferred row just under the higher low (dropping costs a little per dp, rising more:
 *   the user's "not way too low", 2026-06 and 2026-10-31);
 * - some air around it ([Weights.comfortDp]);
 * - never closer than [Weights.clearanceDp] to any ink: a large per-dp penalty, so a label with no
 *   clear spot still takes the least intrusive one.
 */
object NightRainLabelPlacer {
    data class Weights(
        /** Cost per dp² of the label's ink centre off the target centre. */
        val centre: Float = 2f,
        /** Cost per dp below the preferred row. */
        val below: Float = 0.2f,
        /** Cost per dp² below the preferred row: small drops stay cheap, big ones do not ("way too low"). */
        val belowSquared: Float = 0.01f,
        /** Cost per dp above the preferred row. */
        val above: Float = 0.4f,
        /** Cost per dp of air short of [comfortDp], per obstacle. */
        val crowd: Float = 0.5f,
        val comfortDp: Float = 2f,
        /** Minimum ink-to-ink gap side by side or above/below; anything closer costs [intrusion] per dp. */
        val clearanceDp: Float = 1f,
        /**
         * Minimum gap corner to corner (the boxes overlap on neither axis): a diagonal gap reads wider
         * than a side-by-side one. Fold "33%" fitted centred under Fri's 4 only with this, its corner
         * off the top of "Thu" (user, 2026-11-16: "I think it can fit").
         */
        val cornerClearanceDp: Float = 0.5f,
        /** Large enough that any clear spot beats any intrusion, however far it has to drop. */
        val intrusion: Float = 1000f,
        /**
         * Preferred row: this far from the higher low's ink to the label's ink. Matches the line-box
         * tuck the user approved before the redesign (old rows sat 4.3–6.3 dp under the ink where there
         * was room, Fold 2026-11-16). A label rises above it, at [above] per dp, only as far as
         * [clearanceDp] under that low, when the room below is short.
         */
        val rowGapDp: Float = 6f,
        /**
         * How far a label's ink may reach below the day names' ink top, only where no day name is
         * (the names are obstacles too, so [clearanceDp] from their letters still holds). The gap
         * between two names is empty: Fold "33%" missed centre by 2.5 px there (2026-11-16).
         */
        val floorAllowanceDp: Float = 1.5f,
        val stepDp: Float = 0.5f,
    )

    val DEFAULT_WEIGHTS = Weights()

    data class Spot(
        val centerX: Float,
        val top: Float,
        val score: Float,
        val centreCost: Float,
        val verticalCost: Float,
        val crowdCost: Float,
        val intrusionCost: Float,
        /** Smallest ink-to-ink gap to any obstacle, px (negative = overlap); +inf with none. */
        val minGap: Float,
    ) {
        val clear: Boolean get() = intrusionCost == 0f
    }

    data class Result(val best: Spot, val runnerUp: Spot?)

    /**
     * @param width box width; the label is drawn centred on the returned centre.
     * @param ink the label's ink relative to its box's top-left.
     * @param inkBands the same ink as row bands ([InkScan.rowBands]), so the label's own open corners
     *   (the curve of a "3") count as space; defaults to [ink] alone.
     * @param targetCenterX where its ink should be centred (the gap between columns).
     * @param preferredTop box top of the preferred row.
     * @param minTop / [maxInkBottom] vertical range: box top, and lowest ink bottom.
     * @param minLeft / [maxRight] box limits (canvas edge margins).
     * @param maxShift how far the ink centre may move off [targetCenterX].
     */
    fun place(
        width: Float,
        ink: Box,
        inkBands: List<Box> = listOf(ink),
        targetCenterX: Float,
        preferredTop: Float,
        minTop: Float,
        maxInkBottom: Float,
        minLeft: Float,
        maxRight: Float,
        maxShift: Float,
        obstacles: List<Box>,
        density: Float,
        weights: Weights = DEFAULT_WEIGHTS,
    ): Result {
        val p = density
        val invP = 1f / p
        val step = (weights.stepDp * p).coerceAtLeast(0.5f)
        val inkCentreOffset = (ink.left + ink.right) / 2f - width / 2f
        val halfW = width / 2f
        val maxTop = max(minTop, maxInkBottom - ink.bottom)
        val comfortPx = weights.comfortDp * p
        val clearDp = weights.clearanceDp
        val cornerClearDp = weights.cornerClearanceDp
        val comfortDp = weights.comfortDp
        val crowdW = weights.crowd
        val intrusionW = weights.intrusion

        // Pre-filter obstacles to those within comfortPx of ANY candidate spot's ink box, and
        // flatten to primitive FloatArrays so the inner loop allocates zero Box/Spot objects
        // (performance/261118-desktop-api-toggle-latency.md).
        val searchCenterMin = max(minLeft + halfW, targetCenterX - maxShift - inkCentreOffset)
        val searchCenterMax = min(maxRight - halfW, targetCenterX + maxShift - inkCentreOffset)
        val searchLeft = searchCenterMin - halfW + ink.left - comfortPx
        val searchRight = searchCenterMax - halfW + ink.right + comfortPx
        val searchTop = minTop + ink.top - comfortPx
        val searchBottom = maxTop + ink.bottom + comfortPx

        val nearObs = obstacles.filter { o ->
            o.right > searchLeft && o.left < searchRight && o.bottom > searchTop && o.top < searchBottom
        }
        val oCount = nearObs.size
        val oLeft = FloatArray(oCount)
        val oTop = FloatArray(oCount)
        val oRight = FloatArray(oCount)
        val oBottom = FloatArray(oCount)
        for (i in 0 until oCount) {
            val o = nearObs[i]
            oLeft[i] = o.left
            oTop[i] = o.top
            oRight[i] = o.right
            oBottom[i] = o.bottom
        }

        val bandCount = inkBands.size
        val useBands = bandCount > 1
        val bLeft = if (useBands) FloatArray(bandCount) { inkBands[it].left } else FloatArray(0)
        val bTop = if (useBands) FloatArray(bandCount) { inkBands[it].top } else FloatArray(0)
        val bRight = if (useBands) FloatArray(bandCount) { inkBands[it].right } else FloatArray(0)
        val bBottom = if (useBands) FloatArray(bandCount) { inkBands[it].bottom } else FloatArray(0)

        // Pre-build the valid X columns and Y rows so coarse-to-fine indexing is exact.
        val validXs = FloatArray(((2f * maxShift / step).toInt() + 3).coerceAtLeast(1))
        var nx = 0
        var dx = -maxShift
        while (dx <= maxShift + 0.01f) {
            val centerX = targetCenterX + dx - inkCentreOffset
            if (centerX - halfW >= minLeft - 0.01f && centerX + halfW <= maxRight + 0.01f) {
                if (nx == validXs.size) break
                validXs[nx++] = centerX
            }
            dx += step
        }
        val validYs = FloatArray((((maxTop - minTop) / step).toInt() + 3).coerceAtLeast(1))
        var ny = 0
        var topIter = minTop
        while (topIter <= maxTop + 0.01f) {
            if (ny == validYs.size) break
            validYs[ny++] = topIter
            topIter += step
        }

        if (nx == 0 || ny == 0) {
            // Too wide for the canvas limits: centred on the target, on the preferred row.
            val centerX = targetCenterX - inkCentreOffset
            return Result(score(centerX, preferredTop, width, ink, inkBands, targetCenterX, preferredTop, obstacles, p, weights), null)
        }

        // Precompute per-column obstacle index slices once for any column we visit.
        val colObsCache = arrayOfNulls<IntArray>(nx)
        fun colObsFor(ix: Int): IntArray {
            colObsCache[ix]?.let { return it }
            val left = validXs[ix] - halfW
            val lLeft = left + ink.left
            val lRight = left + ink.right
            var count = 0
            for (i in 0 until oCount) {
                if (max(lLeft - oRight[i], oLeft[i] - lRight) < comfortPx) count++
            }
            val arr = IntArray(count)
            var k = 0
            for (i in 0 until oCount) {
                if (max(lLeft - oRight[i], oLeft[i] - lRight) < comfortPx) arr[k++] = i
            }
            colObsCache[ix] = arr
            return arr
        }

        val colCentreCost = FloatArray(nx) { ix ->
            val left = validXs[ix] - halfW
            val offDp = ((left + ink.left + left + ink.right) * 0.5f - targetCenterX) * invP
            weights.centre * offDp * offDp
        }
        val colDxTarget = FloatArray(nx) { ix -> abs(validXs[ix] + inkCentreOffset - targetCenterX) }
        val rowVerticalCost = FloatArray(ny) { iy ->
            val dyDp = (validYs[iy] - preferredTop) * invP
            if (dyDp > 0f) weights.below * dyDp + weights.belowSquared * dyDp * dyDp else weights.above * -dyDp
        }
        val rowDyPref = FloatArray(ny) { iy -> abs(validYs[iy] - preferredTop) }

        val totalGrid = nx * ny
        val clearPx = clearDp * p
        val useCoarseToFine = totalGrid > 180
        val initCap = if (useCoarseToFine) min(totalGrid, 512) else totalGrid
        var spotCx = FloatArray(initCap)
        var spotTop = FloatArray(initCap)
        var spotScore = FloatArray(initCap)
        var spotCount = 0

        var bestIdx = -1
        var bestScore = Float.POSITIVE_INFINITY
        var bestDyPref = Float.POSITIVE_INFINITY
        var bestDxTarget = Float.POSITIVE_INFINITY

        fun evalGridCell(ix: Int, iy: Int): Float {
            val centerX = validXs[ix]
            val top = validYs[iy]
            val left = centerX - halfW
            val lLeft = left + ink.left
            val lRight = left + ink.right
            val lTop = top + ink.top
            val lBottom = top + ink.bottom
            val colObs = colObsFor(ix)
            var crowdCost = 0f
            var intrusionCost = 0f

            for (k in colObs.indices) {
                val i = colObs[k]
                val obTop = oTop[i]
                val obBottom = oBottom[i]
                val ody = max(lTop - obBottom, obTop - lBottom)
                if (ody >= comfortPx) continue
                val obLeft = oLeft[i]
                val obRight = oRight[i]
                val odx = max(lLeft - obRight, obLeft - lRight)
                var g = rawGap(odx, ody)
                if (g >= comfortPx) continue
                var shortDp = (if (odx > 0f && ody > 0f) cornerClearDp else clearDp) - g * invP
                if (useBands) {
                    g = Float.POSITIVE_INFINITY
                    shortDp = Float.NEGATIVE_INFINITY
                    for (j in 0 until bandCount) {
                        val bdY = max(top + bTop[j] - obBottom, obTop - (top + bBottom[j]))
                        if (g >= 0f && bdY >= g && bdY >= clearPx) continue
                        val bdX = max(left + bLeft[j] - obRight, obLeft - (left + bRight[j]))
                        if (g >= 0f && bdX >= g && bdX >= clearPx) continue
                        val bg = rawGap(bdX, bdY)
                        if (bg < g) g = bg
                        val bs = (if (bdX > 0f && bdY > 0f) cornerClearDp else clearDp) - bg * invP
                        if (bs > shortDp) shortDp = bs
                    }
                }
                val gapDp = g * invP
                if (gapDp < comfortDp) crowdCost += crowdW * (comfortDp - max(gapDp, 0f))
                if (shortDp > 0f) intrusionCost += intrusionW * shortDp
            }

            val totalScore = colCentreCost[ix] + rowVerticalCost[iy] + crowdCost + intrusionCost
            if (spotCount == spotCx.size) {
                val newCap = spotCx.size * 2
                spotCx = spotCx.copyOf(newCap)
                spotTop = spotTop.copyOf(newCap)
                spotScore = spotScore.copyOf(newCap)
            }
            val idx = spotCount++
            spotCx[idx] = centerX
            spotTop[idx] = top
            spotScore[idx] = totalScore

            val dyPref = rowDyPref[iy]
            val dxTarget = colDxTarget[ix]
            if (
                totalScore < bestScore ||
                (totalScore == bestScore && (dyPref < bestDyPref || (dyPref == bestDyPref && dxTarget < bestDxTarget)))
            ) {
                bestIdx = idx
                bestScore = totalScore
                bestDyPref = dyPref
                bestDxTarget = dxTarget
            }
            return totalScore
        }

        if (!useCoarseToFine) {
            for (ix in 0 until nx) {
                for (iy in 0 until ny) {
                    evalGridCell(ix, iy)
                }
            }
        } else {
            // Coarse-to-fine: sample every 3 steps (1.5 dp), then refine at full 0.5 dp around the
            // top coarse seeds and the preferred origin so clear pockets are never missed while
            // cutting grid evaluations ~6-8x on both desktop and Android.
            val stride = 3
            val visited = BooleanArray(totalGrid)
            val topK = 12
            val seedIx = IntArray(topK)
            val seedIy = IntArray(topK)
            val seedScore = FloatArray(topK) { Float.POSITIVE_INFINITY }
            var seedCount = 0

            fun considerSeed(ix: Int, iy: Int, s: Float) {
                if (seedCount < topK) {
                    var pos = seedCount++
                    while (pos > 0 && s < seedScore[pos - 1]) {
                        seedScore[pos] = seedScore[pos - 1]
                        seedIx[pos] = seedIx[pos - 1]
                        seedIy[pos] = seedIy[pos - 1]
                        pos--
                    }
                    seedScore[pos] = s
                    seedIx[pos] = ix
                    seedIy[pos] = iy
                } else if (s < seedScore[topK - 1]) {
                    var pos = topK - 1
                    while (pos > 0 && s < seedScore[pos - 1]) {
                        seedScore[pos] = seedScore[pos - 1]
                        seedIx[pos] = seedIx[pos - 1]
                        seedIy[pos] = seedIy[pos - 1]
                        pos--
                    }
                    seedScore[pos] = s
                    seedIx[pos] = ix
                    seedIy[pos] = iy
                }
            }

            fun sampleCoarse(ix: Int, iy: Int) {
                val flat = ix * ny + iy
                if (visited[flat]) return
                visited[flat] = true
                considerSeed(ix, iy, evalGridCell(ix, iy))
            }

            var ix = 0
            while (ix < nx) {
                var iy = 0
                while (iy < ny) {
                    sampleCoarse(ix, iy)
                    iy = if (iy + stride < ny) iy + stride else if (iy < ny - 1) ny - 1 else ny
                }
                ix = if (ix + stride < nx) ix + stride else if (ix < nx - 1) nx - 1 else nx
            }

            // Refine around every top coarse seed.
            for (s in 0 until seedCount) {
                val cIx = seedIx[s]
                val cIy = seedIy[s]
                val x0 = (cIx - stride).coerceAtLeast(0)
                val x1 = (cIx + stride).coerceAtMost(nx - 1)
                val y0 = (cIy - stride).coerceAtLeast(0)
                val y1 = (cIy + stride).coerceAtMost(ny - 1)
                for (rx in x0..x1) {
                    val base = rx * ny
                    for (ry in y0..y1) {
                        val flat = base + ry
                        if (!visited[flat]) {
                            visited[flat] = true
                            evalGridCell(rx, ry)
                        }
                    }
                }
            }
        }

        val bestCx = spotCx[bestIdx]
        val bestTop = spotTop[bestIdx]
        val best = score(bestCx, bestTop, width, ink, inkBands, targetCenterX, preferredTop, obstacles, p, weights)

        // The runner-up worth naming in the trace: the best spot more than 2 dp away.
        val minFarDistSq = (2f * p) * (2f * p)
        var runnerIdx = -1
        var runnerScore = Float.POSITIVE_INFINITY
        var runnerDyPref = Float.POSITIVE_INFINITY
        var runnerDxTarget = Float.POSITIVE_INFINITY
        for (i in 0 until spotCount) {
            val s = spotScore[i]
            if (s > runnerScore) continue
            val ddx = spotCx[i] - bestCx
            val ddy = spotTop[i] - bestTop
            if (ddx * ddx + ddy * ddy <= minFarDistSq) continue
            val dyPref = abs(spotTop[i] - preferredTop)
            val dxTarget = abs(spotCx[i] + inkCentreOffset - targetCenterX)
            if (
                s < runnerScore ||
                (s == runnerScore && (dyPref < runnerDyPref || (dyPref == runnerDyPref && dxTarget < runnerDxTarget)))
            ) {
                runnerIdx = i
                runnerScore = s
                runnerDyPref = dyPref
                runnerDxTarget = dxTarget
            }
        }
        val runnerUp = if (runnerIdx >= 0) {
            score(spotCx[runnerIdx], spotTop[runnerIdx], width, ink, inkBands, targetCenterX, preferredTop, obstacles, p, weights)
        } else {
            null
        }
        return Result(best, runnerUp)
    }

    private fun rawGap(dx: Float, dy: Float): Float = when {
        dx >= 0f && dy >= 0f -> hypot(dx, dy)
        dx >= 0f -> dx
        dy >= 0f -> dy
        else -> max(dx, dy)
    }

    private fun score(
        centerX: Float,
        top: Float,
        width: Float,
        ink: Box,
        inkBands: List<Box>,
        targetCenterX: Float,
        preferredTop: Float,
        obstacles: List<Box>,
        p: Float,
        w: Weights,
    ): Spot {
        val left = centerX - width / 2f
        val labelInk = Box(left + ink.left, top + ink.top, left + ink.right, top + ink.bottom)
        val offDp = ((labelInk.left + labelInk.right) / 2f - targetCenterX) / p
        val centreCost = w.centre * offDp * offDp
        val dyDp = (top - preferredTop) / p
        val verticalCost = if (dyDp > 0f) w.below * dyDp + w.belowSquared * dyDp * dyDp else w.above * -dyDp
        var crowdCost = 0f
        var intrusionCost = 0f
        var minGap = Float.POSITIVE_INFINITY
        for (o in obstacles) {
            var gap = gap(labelInk, o)
            var shortDp = (if (isCorner(labelInk, o)) w.cornerClearanceDp else w.clearanceDp) - gap / p
            // Near enough to matter: measure from the label's bands, not its bounding box.
            if (gap < w.comfortDp * p && inkBands.size > 1) {
                gap = Float.POSITIVE_INFINITY
                shortDp = Float.NEGATIVE_INFINITY
                for (b in inkBands) {
                    val band = Box(left + b.left, top + b.top, left + b.right, top + b.bottom)
                    val g = gap(band, o)
                    gap = min(gap, g)
                    shortDp = max(shortDp, (if (isCorner(band, o)) w.cornerClearanceDp else w.clearanceDp) - g / p)
                }
            }
            minGap = min(minGap, gap)
            val gapDp = gap / p
            if (gapDp < w.comfortDp) crowdCost += w.crowd * (w.comfortDp - max(gapDp, 0f))
            if (shortDp > 0f) intrusionCost += w.intrusion * shortDp
        }
        return Spot(
            centerX, top, centreCost + verticalCost + crowdCost + intrusionCost,
            centreCost, verticalCost, crowdCost, intrusionCost, minGap,
        )
    }

    /** True when [a] and [b] overlap on neither axis: they face each other corner to corner. */
    fun isCorner(a: Box, b: Box): Boolean =
        max(a.left - b.right, b.left - a.right) > 0f && max(a.top - b.bottom, b.top - a.bottom) > 0f

    /** Ink-to-ink distance between two boxes; negative by the shallower penetration when they overlap. */
    fun gap(a: Box, b: Box): Float {
        val dx = max(a.left - b.right, b.left - a.right)
        val dy = max(a.top - b.bottom, b.top - a.bottom)
        return when {
            dx >= 0f && dy >= 0f -> hypot(dx, dy)
            dx >= 0f -> dx
            dy >= 0f -> dy
            else -> max(dx, dy)
        }
    }
}
