package com.weatherwidget.shared.graph

import java.time.Duration
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure geometry/time math for the temperature label engine: the zoom-aware redundancy windows, the
 * flat-run centering, and the per-candidate geometry resolution ([resolve]) that turns a
 * [TempLabelCandidate] into a [ResolvedLabelGeometry]. Extracted from [TemperatureLabelResolver]
 * so the resolver stays a thin facade over the candidate/suppression/geometry pipelines.
 */
internal object LabelGeometryResolver {

    // Two same-ish-valued labels read as a redundant pair only when they sit close together ON
    // SCREEN. Index distance is a poor proxy because pixels-per-hour changes with zoom: 3 hours is
    // ~85px on a zoomed-in day view but only ~24px on a 3-day view. So derive the index window from
    // an on-screen pixel budget whenever the pixel width is known, and fall back to the legacy
    // index heuristic for callers without geometry (direct unit tests).
    internal const val REDUNDANT_PAIR_PX = 64f
    private const val REDUNDANT_PAIR_WINDOW_CAP = 8

    // A boundary START/END label is redundant with a pixel-near SAME-SERIES forecast extreme when
    // their DISPLAYED values are within this many degrees — matches how the user reads "73 ≈ 75".
    // Looser than the strict cross-series (actual) gate, which stays < 2f so forecast-vs-actual pairs
    // (different series the user compares side by side) keep both labels.
    internal const val SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES = 2

    /**
     * Index window within which a nearby labeled extremum can mark this candidate redundant.
     * Zoom-aware when [widthPx] > 0 (converts [REDUNDANT_PAIR_PX] through the view's px-per-hour);
     * otherwise the historical `min(cap, lastIndex/5)` heuristic.
     */
    internal fun computeRedundantPairWindow(hours: List<HourData>, widthPx: Int): Int {
        val lastIndex = hours.lastIndex
        if (lastIndex <= 0) return 0
        val legacy = min(REDUNDANT_PAIR_WINDOW_CAP, lastIndex / 5)
        if (widthPx <= 0) return legacy
        val spanHours = Duration.between(hours.first().dateTime, hours.last().dateTime).toMinutes() / 60f
        if (spanHours <= 0f) return legacy
        val pxPerHour = widthPx / spanHours
        val hoursPerIndex = spanHours / lastIndex
        val windowIndices = (REDUNDANT_PAIR_PX / pxPerHour) / hoursPerIndex
        return windowIndices.roundToInt().coerceIn(1, REDUNDANT_PAIR_WINDOW_CAP)
    }

    // Two values belong to the same flat run when they differ by less than this.
    private const val RUN_EQUAL_EPSILON = 0.01f

    // Roles drawn at the CENTER of their equal-value run rather than at their own index (see
    // [centerOfRun]): a flat 67° plateau carries ONE label in its middle, not one at its left edge.
    // Redundancy is a question about the DRAWN position, so [anchorMinutes] must apply the same
    // centering — otherwise a plateau LOW is measured where it is not drawn.
    private val RUN_CENTERED_ROLES = setOf(
        TemperatureRole.LOW, TemperatureRole.HIGH,
        TemperatureRole.FORECAST_LOW, TemperatureRole.FORECAST_HIGH,
        TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH,
        TemperatureRole.LOCAL,
    )

    /** Inclusive bounds of the flat run of equal values containing [idx]. */
    internal fun runBounds(temps: List<Float>, idx: Int): IntRange {
        val v = temps[idx]
        var first = idx
        var last = idx
        while (first > 0 && abs(temps[first - 1] - v) < RUN_EQUAL_EPSILON) first--
        while (last < temps.lastIndex && abs(temps[last + 1] - v) < RUN_EQUAL_EPSILON) last++
        return first..last
    }

    /**
     * Minutes from the window start to where the label for [idx] is actually DRAWN. Run-centered
     * roles resolve to the midpoint of their flat run, mirroring [centerOfRun]; all other roles sit
     * on their own index. The renderer maps x linearly in time, so the midpoint of two timestamps is
     * exactly the midpoint of the two x's that [centerOfRun] averages.
     */
    private fun anchorMinutes(hours: List<HourData>, idx: Int, role: TemperatureRole, temps: List<Float>): Float {
        val start = hours.first().dateTime
        fun minutesAt(i: Int) = Duration.between(start, hours[i].dateTime).toMinutes().toFloat()
        if (role !in RUN_CENTERED_ROLES || idx > temps.lastIndex) return minutesAt(idx)
        val run = runBounds(temps, idx)
        return (minutesAt(run.first) + minutesAt(run.last)) / 2f
    }

    /**
     * On-screen horizontal gap (px) between where two labels are DRAWN, derived from their real
     * timestamps rather than index position. A single averaged px-per-hour (as in
     * [computeRedundantPairWindow]) cannot see that the densely-sampled observed region near an edge
     * packs many indices into a few pixels — e.g. on a 3-day view idx 0 and idx 30 can be ~5px apart
     * yet 30 indices apart. This per-pair measure is exact for non-uniform sampling. Returns
     * [Float.MAX_VALUE] when geometry is unknown (widthPx<=0) or the span is degenerate, so
     * geometry-less unit tests fall back to never-near.
     */
    internal fun pixelGapByTime(
        hours: List<HourData>,
        idxA: Int,
        roleA: TemperatureRole,
        tempsA: List<Float>,
        idxB: Int,
        roleB: TemperatureRole,
        tempsB: List<Float>,
        widthPx: Int,
    ): Float {
        if (widthPx <= 0) return Float.MAX_VALUE
        if (hours.getOrNull(idxA) == null || hours.getOrNull(idxB) == null) return Float.MAX_VALUE
        val spanMinutes = Duration.between(hours.first().dateTime, hours.last().dateTime).toMinutes()
        if (spanMinutes <= 0L) return Float.MAX_VALUE
        val pairMinutes = abs(anchorMinutes(hours, idxA, roleA, tempsA) - anchorMinutes(hours, idxB, roleB, tempsB))
        return pairMinutes / spanMinutes.toFloat() * widthPx
    }

    internal fun sortCandidates(candidates: MutableList<TempLabelCandidate>) {
        candidates.sortWith(
            compareBy<TempLabelCandidate> {
                when (it.role) {
                    TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW -> 0
                    TemperatureRole.LOCAL, TemperatureRole.ACTUAL_END -> 1
                    else -> 2 // START, END
                }
            }.thenBy {
                val displayTemp = it.labelTemps[it.index]
                val leftVal = findPrevDifferent(it.labelTemps, it.index)
                val rightVal = findNextDifferent(it.labelTemps, it.index)
                val isPeak = it.role in listOf(TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.PAST_FORECAST_HIGH) || (it.role == TemperatureRole.LOCAL && displayTemp > leftVal && displayTemp > rightVal)
                if (isPeak) -displayTemp else displayTemp
            }
        )
    }

    private fun findPrevDifferent(temps: List<Float>, idx: Int): Float {
        val target = temps[idx]
        for (i in idx - 1 downTo 0) {
            if (temps[i] != target) return temps[i]
        }
        return target
    }

    private fun findNextDifferent(temps: List<Float>, idx: Int): Float {
        val target = temps[idx]
        for (i in idx + 1..temps.lastIndex) {
            if (temps[i] != target) return temps[i]
        }
        return target
    }

    internal fun resolve(
        candidate: TempLabelCandidate,
        originalPoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        transitionX: Float?,
        widthPx: Int,
        density: Float,
        fetchDotX: Float?,
        lastObservedTemp: Float?,
        tempToY: (Float) -> Float,
        metrics: LabelTextMetrics,
        useCelsius: Boolean,
    ): ResolvedLabelGeometry? {
        val idx = candidate.index
        val temps = candidate.labelTemps
        val isFuture = candidate.forceForecastSeries || (originalPoints.getOrNull(idx)?.first ?: 0f) > (transitionX ?: -1f)
        val points = if (isFuture) forecastPoints else originalPoints
        val sx = if (candidate.role in listOf(
                TemperatureRole.LOW, TemperatureRole.HIGH,
                TemperatureRole.FORECAST_LOW, TemperatureRole.FORECAST_HIGH,
                TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH,
                TemperatureRole.LOCAL
            )) {
            centerOfRun(idx, temps, candidate.forceForecastSeries, originalPoints, forecastPoints, transitionX).first
        } else {
            points.getOrNull(idx)?.first ?: 0f
        }
        val sy = tempToY(temps[idx])

        val label = TemperatureLabelResolver.formatTemp(temps[idx], useCelsius) + "°"
        val textWidth = metrics.width(label, isFuture)
        val clampedX = sx.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)

        if (fetchDotX != null && lastObservedTemp != null && candidate.role !in setOf(TemperatureRole.START, TemperatureRole.END)) {
            val fetchDotLabel = TemperatureLabelResolver.formatTemp(lastObservedTemp, useCelsius) + "°"
            val dist = abs(clampedX - fetchDotX)
            if (label == fetchDotLabel && dist < 12f * density) {
                return null
            }
        }

        val leftVal = findPrevDifferent(temps, idx)
        val rightVal = findNextDifferent(temps, idx)
        val isValley = candidate.role in listOf(TemperatureRole.LOW, TemperatureRole.FORECAST_LOW, TemperatureRole.ACTUAL_LOW, TemperatureRole.PAST_FORECAST_LOW) ||
            (candidate.role == TemperatureRole.LOCAL && temps[idx] < leftVal && temps[idx] < rightVal)
        val isEssential = candidate.role in TemperatureLabelResolver.ESSENTIAL_LABEL_ROLES

        return ResolvedLabelGeometry(
            index = idx,
            role = candidate.role,
            rawTemperature = candidate.rawTemperature,
            displayTemperature = temps[idx],
            label = label,
            isFuture = isFuture,
            isValley = isValley,
            isEssential = isEssential,
            sx = sx,
            sy = sy,
            textWidth = textWidth,
            clampedX = clampedX,
        )
    }

    private fun centerOfRun(
        idx: Int,
        temps: List<Float>,
        forceForecast: Boolean,
        original: List<Pair<Float, Float>>,
        forecast: List<Pair<Float, Float>>,
        transitionX: Float?,
    ): Pair<Float, Float> {
        val run = runBounds(temps, idx)
        val points = if (forceForecast || (original.getOrNull(idx)?.first ?: 0f) > (transitionX ?: -1f)) forecast else original
        val fPoint = points.getOrNull(run.first) ?: (0f to 0f)
        val lPoint = points.getOrNull(run.last) ?: (0f to 0f)
        return (fPoint.first + lPoint.first) / 2f to (fPoint.second + lPoint.second) / 2f
    }
}
