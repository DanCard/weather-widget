package com.weatherwidget.shared.graph

/**
 * Where the precipitation graph's "Sim loop" pill goes: **empty space in the plot**, wherever the
 * data leaves it — not a fixed corner.
 *
 * The first version (2026-10-20) hard-coded the upper-right corner. On a Pixel 7 Pro that put the
 * pill directly under the source label and settings gear, above the plot rather than in it, and
 * on a wet evening the corner holds the late-window "5%" and "Pred …in" labels anyway. The search
 * is [GraphEmptySpaceFinder], the same one the station and delta labels use, so Android's bitmap
 * and desktop's Compose canvas pick the same slot. See
 * `plans/261020-sim-loop-pill-free-space-placement-and-review-fixes.md`.
 *
 * When no slot clears the curve — spiky hourly chances leave valleys narrower than the pill — it
 * may sit over the curve ([Placement.overCurve]) but still never over a label or the NOW line /
 * nav arrows: hiding the button on exactly the busy rain days it exists for would be worse. Null
 * only when even that fails.
 */
object SimLoopPill {
    /** Drawn pill height. */
    const val HEIGHT_DP = 22f

    /** Space between the label and the pill's rounded ends. */
    const val HORIZONTAL_PADDING_DP = 9f

    /** Minimum air kept between the pill and the curve or any label. */
    const val CLEARANCE_DP = 6f

    /** Tap target floor (Material's 48dp), centred on the pill. */
    const val MIN_TOUCH_HEIGHT_DP = 48f
    const val MIN_TOUCH_WIDTH_DP = 64f

    /** Right-leaning preference: the pill reads as a control, and the left holds "Tue" and the 0% start. */
    val X_FRACTIONS = listOf(0.9f, 0.72f, 0.5f, 0.28f, 0.1f)

    data class Placement(val box: GraphRect, val overCurve: Boolean)

    fun pillWidthPx(labelWidthPx: Float, density: Float): Float = labelWidthPx + 2f * HORIZONTAL_PADDING_DP * density

    /**
     * The pill's placement in [plot]'s coordinates, or null when no slot clears the labels.
     *
     * [curvePoints] are the precipitation curve's vertices (x ascending). Besides keeping clear of
     * the line, the pill must not sit in the filled area under it, which the finder's `curveYsAt`
     * would happily accept as "below the curve" — so each segment's fill becomes a veto column.
     * [drawnBounds] are labels already on the canvas; [vetoBounds] are the NOW line and nav arrows.
     */
    fun place(
        plot: GraphRect,
        curvePoints: List<Pair<Float, Float>>,
        drawnBounds: List<GraphRect>,
        vetoBounds: List<GraphRect>,
        pillWidthPx: Float,
        density: Float,
    ): Placement? {
        if (curvePoints.size < 2) return null
        val heightPx = HEIGHT_DP * density
        val padPx = CLEARANCE_DP * density
        val metrics = GraphEmptySpaceFinder.Metrics(width = pillWidthPx, ascent = -heightPx, descent = 0f)
        GraphEmptySpaceFinder.find(
            plot = plot,
            drawnBounds = drawnBounds,
            curveYsAt = { x -> curveYAt(curvePoints, x)?.let { listOf(it) } ?: emptyList() },
            metrics = metrics,
            padPx = padPx,
            xFractions = X_FRACTIONS,
            vetoBounds = vetoBounds + fillColumns(curvePoints, plot.bottom),
        )?.let { return Placement(it.box, overCurve = false) }
        return GraphEmptySpaceFinder.find(
            plot = plot,
            drawnBounds = drawnBounds,
            curveYsAt = { emptyList() },
            metrics = metrics,
            padPx = padPx,
            xFractions = X_FRACTIONS,
            vetoBounds = vetoBounds,
        )?.let { Placement(it.box, overCurve = true) }
    }

    /** Where both of the graph's pills went; null = not offered or no room. */
    data class Pills(val radar: Placement?, val simLoop: Placement?)

    /**
     * The precipitation graph's two pills: **"Radar" first**, then "Sim loop" in the space left,
     * clear of the radar pill. Radar goes first because it works wherever the graph has a location
     * (`plans/261021-radar-button-on-precip-graph.md`); Sim loop is source/region-limited, so when
     * only one fits it is the one dropped. A null width = that pill is not offered.
     */
    fun placeRadarAndSimLoop(
        plot: GraphRect,
        curvePoints: List<Pair<Float, Float>>,
        drawnBounds: List<GraphRect>,
        vetoBounds: List<GraphRect>,
        radarWidthPx: Float?,
        simLoopWidthPx: Float?,
        density: Float,
    ): Pills {
        val radar = radarWidthPx?.let { place(plot, curvePoints, drawnBounds, vetoBounds, it, density) }
        val simLoop = simLoopWidthPx?.let {
            place(plot, curvePoints, drawnBounds + listOfNotNull(radar?.box), vetoBounds, it, density)
        }
        return Pills(radar, simLoop)
    }

    /**
     * The tap target for a placed [pill]: grown to the minimum touch size around its centre, then
     * kept inside [container] and clear of [avoid] (header buttons) by trimming the side that hits
     * them. Never smaller than the pill itself.
     */
    fun touchBounds(
        pill: GraphRect,
        density: Float,
        container: GraphRect,
        avoid: List<GraphRect> = emptyList(),
    ): GraphRect {
        val w = maxOf(pill.width, MIN_TOUCH_WIDTH_DP * density)
        val h = maxOf(pill.height, MIN_TOUCH_HEIGHT_DP * density)
        val cx = (pill.left + pill.right) / 2f
        val cy = (pill.top + pill.bottom) / 2f
        var r =
            GraphRect(
                left = maxOf(container.left, cx - w / 2f),
                top = maxOf(container.top, cy - h / 2f),
                right = minOf(container.right, cx + w / 2f),
                bottom = minOf(container.bottom, cy + h / 2f),
            )
        for (a in avoid) {
            if (!a.intersects(r)) continue
            // Trim whichever edge gives up the least, never past the pill.
            val trims =
                listOf(
                    r.copy(top = maxOf(r.top, minOf(a.bottom, pill.top))),
                    r.copy(bottom = minOf(r.bottom, maxOf(a.top, pill.bottom))),
                    r.copy(left = maxOf(r.left, minOf(a.right, pill.left))),
                    r.copy(right = minOf(r.right, maxOf(a.left, pill.right))),
                ).filter { !it.intersects(a) }
            r = trims.maxByOrNull { it.width * it.height } ?: r
        }
        return r
    }

    /** Linear interpolation of the curve at [x]; null outside it. */
    internal fun curveYAt(points: List<Pair<Float, Float>>, x: Float): Float? {
        if (points.isEmpty() || x < points.first().first || x > points.last().first) return null
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (x in x0..x1) {
                if (x1 == x0) return minOf(y0, y1)
                return y0 + (y1 - y0) * ((x - x0) / (x1 - x0))
            }
        }
        return points.last().second
    }

    /** The filled area under each curve segment, topped at the segment's higher end. */
    internal fun fillColumns(points: List<Pair<Float, Float>>, bottom: Float): List<GraphRect> =
        points.zipWithNext { (x0, y0), (x1, y1) -> GraphRect(x0, minOf(y0, y1), x1, bottom) }
            .filter { it.top < it.bottom && it.left < it.right }
}
