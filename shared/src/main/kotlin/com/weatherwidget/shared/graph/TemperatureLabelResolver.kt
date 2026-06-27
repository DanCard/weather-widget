package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.round

data class ResolvedLabelGeometry(
    val index: Int,
    val role: TemperatureRole,
    val rawTemperature: Float,
    val displayTemperature: Float,
    val label: String,
    val isFuture: Boolean,
    val isValley: Boolean,
    val isEssential: Boolean,
    val sx: Float,
    val sy: Float,
    val textWidth: Float,
    val clampedX: Float,
)

object TemperatureLabelResolver {
    private const val TAG = "TempLabelResolver"
    private const val MAX_TEMP_LABEL_CANDIDATES = 6
    private val DENSE_TEMP_DIFF_THRESHOLDS = listOf(3, 4, 5)
    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 2.5f

    // Two same-ish-valued labels read as a redundant pair only when they sit close together ON
    // SCREEN. Index distance is a poor proxy because pixels-per-hour changes with zoom: 3 hours is
    // ~85px on a zoomed-in day view but only ~24px on a 3-day view. So derive the index window from
    // an on-screen pixel budget whenever the pixel width is known, and fall back to the legacy
    // index heuristic for callers without geometry (direct unit tests).
    private const val REDUNDANT_PAIR_PX = 64f
    private const val REDUNDANT_PAIR_WINDOW_CAP = 8

    // A boundary START/END label is redundant with a pixel-near SAME-SERIES forecast extreme when
    // their DISPLAYED values are within this many degrees — matches how the user reads "73 ≈ 75".
    // Looser than the strict cross-series (actual) gate, which stays < 2f so forecast-vs-actual pairs
    // (different series the user compares side by side) keep both labels.
    private const val SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES = 2

    /**
     * Index window within which a nearby labeled extremum can mark this candidate redundant.
     * Zoom-aware when [widthPx] > 0 (converts [REDUNDANT_PAIR_PX] through the view's px-per-hour);
     * otherwise the historical `min(cap, lastIndex/5)` heuristic.
     */
    private fun computeRedundantPairWindow(hours: List<HourData>, widthPx: Int): Int {
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

    /**
     * On-screen horizontal gap (px) between two indices, derived from their real timestamps rather
     * than index position. A single averaged px-per-hour (as in [computeRedundantPairWindow]) cannot
     * see that the densely-sampled observed region near an edge packs many indices into a few pixels
     * — e.g. on a 3-day view idx 0 and idx 30 can be ~5px apart yet 30 indices apart. This per-pair
     * measure is exact for non-uniform sampling. Returns [Float.MAX_VALUE] when geometry is unknown
     * (widthPx<=0) or the span is degenerate, so geometry-less unit tests fall back to never-near.
     */
    private fun pixelGapByTime(hours: List<HourData>, idxA: Int, idxB: Int, widthPx: Int): Float {
        if (widthPx <= 0) return Float.MAX_VALUE
        val a = hours.getOrNull(idxA) ?: return Float.MAX_VALUE
        val b = hours.getOrNull(idxB) ?: return Float.MAX_VALUE
        val spanMinutes = Duration.between(hours.first().dateTime, hours.last().dateTime).toMinutes()
        if (spanMinutes <= 0L) return Float.MAX_VALUE
        val pairMinutes = abs(Duration.between(a.dateTime, b.dateTime).toMinutes())
        return pairMinutes.toFloat() / spanMinutes.toFloat() * widthPx
    }

    val ESSENTIAL_LABEL_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.LOW,
        TemperatureRole.HIGH,
        TemperatureRole.FORECAST_LOW,
        TemperatureRole.FORECAST_HIGH,
        TemperatureRole.ACTUAL_LOW,
        TemperatureRole.ACTUAL_HIGH,
        TemperatureRole.PAST_FORECAST_LOW,
        TemperatureRole.PAST_FORECAST_HIGH,
        TemperatureRole.LOCAL,
        TemperatureRole.START,
        TemperatureRole.END,
        TemperatureRole.ACTUAL_END,
    )

    // Roles whose suppress/accept decisions are traced to logcat (Log.v — ephemeral, never persisted
    // to app_logs). Includes the FORECAST_* roles so a near-edge forecast extreme that gets dropped
    // is no longer invisible: the "right-side high not labeled" bug was a silent FORECAST_HIGH drop.
    private val LOGGED_SUPPRESSION_ROLES = setOf(
        TemperatureRole.ACTUAL_HIGH, TemperatureRole.HIGH,
        TemperatureRole.ACTUAL_LOW, TemperatureRole.LOW,
        TemperatureRole.ACTUAL_END, TemperatureRole.END,
        TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
        TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
    )

    data class SuppressionResult(
        val suppressed: Boolean,
        val overriddenRole: TemperatureRole? = null,
    )

    fun formatTemp(value: Float): String {
        val rounded = round(value * 10f) / 10f
        return if (rounded % 1f == 0f) {
            "%.0f".format(rounded)
        } else {
            "%.1f".format(rounded)
        }
    }

    // Where a label's value came from — so a "what is this number / is it interpolated?" question is
    // answerable straight from the log, without re-deriving the dense series by hand:
    //   OBSERVED          = the measured/blended actual line (actualLabelTemps)
    //   SMOOTHED_FORECAST = the smoothed forecast curve at a real extremum/endpoint (labelTemps)
    //   SMOOTHED_MIDPOINT = a synthesized anchor dropped on a bare monotonic forecast stretch —
    //                       interpolated, NOT a data point (this is the one that reads e.g. "73.8°"
    //                       sitting between a 74° start and a 72° end). See addForecastMidpointLabel.
    private fun provenanceFor(role: TemperatureRole, isMidpoint: Boolean): String = when {
        isMidpoint -> "SMOOTHED_MIDPOINT"
        role in ACTUAL_DISPLAY_ROLES -> "OBSERVED"
        else -> "SMOOTHED_FORECAST"
    }

    // One enriched label-decision line: the on-screen string, its clock time, role, why, and value
    // provenance — enough to map a label seen on the graph back to its origin in a single grep.
    // Emitted at VERBOSE (via Log.v, like every breadcrumb in this per-render engine): visible in the
    // ephemeral sink (logcat / desktop console) but never persisted to app_logs, which is reserved for
    // sparse events and would otherwise be swamped (as the CurrentTempResolver tag once was).
    private fun logLabelDecision(
        action: String,
        role: TemperatureRole,
        idx: Int,
        value: Float,
        hours: List<HourData>,
        reason: String,
        provenance: String,
        extra: String = "",
    ) {
        val t = hours.getOrNull(idx)?.dateTime?.toLocalTime()?.toString() ?: "?"
        // No degree glyph: the file log sink isn't UTF-8 and renders ° as '?'. The bare number still
        // greps against what's on screen (e.g. `grep 'displayed="73.8'`).
        Log.v(
            TAG,
            "$action: displayed=\"${formatTemp(value)}\" t=$t role=$role reason=$reason " +
                "provenance=$provenance val=$value idx=$idx" + if (extra.isEmpty()) "" else " $extra",
        )
    }

    fun computeExtremaIndices(
        hours: List<HourData>,
        transitionX: Float?,
        effectiveActualEndIndex: Int,
        fetchTime: LocalDateTime?,
    ): TemperatureExtrema.ExtremaIndices {
        val prominenceThreshold = when {
            hours.size <= 10 -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
            hours.size <= 24 -> 1.5f // Narrow zoom: more detail, but still reject minor noise
            else -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
        }
        return TemperatureExtrema.compute(hours, transitionX, effectiveActualEndIndex, fetchTime, prominenceThreshold)
    }

    fun collectLabelCandidates(
        hours: List<HourData>,
        extrema: TemperatureExtrema.ExtremaIndices,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        observedAt: Long?,
        numColumns: Int = 0,
        widthPx: Int = 0,
    ): List<TempLabelCandidate> {
        val labelTemps = extrema.labelTemps
        val actualLabelTemps = extrema.actualLabelTemps

        // Boundary labels (START/END) are positional anchors, so "nearby" for them is a visual
        // (zoom-aware) pixel budget. Extrema-vs-extrema redundancy (forecast-vs-actual high/low) is
        // about the SAME semantic quantity and keeps the legacy index window inside the check.
        val boundaryRedundancyWindow = computeRedundantPairWindow(hours, widthPx)
        Log.v(TAG, "RedundancyWindow: boundary=$boundaryRedundancyWindow widthPx=$widthPx hours=${hours.size}")

        val potentialAnchors = buildPotentialAnchors(extrema, hours.size)
        extrema.significantLocalExtrema.forEach { potentialAnchors.add(it to TemperatureRole.LOCAL) }
        Log.v(TAG, "Potential anchors: $potentialAnchors")

        // Value used by the dense-filter / suppression passes, which round to an Int (roundToInt throws
        // on NaN). A window can extend past the loaded forecast horizon, leaving hours with a NaN
        // forecast temp. Where the forecast is NaN but the point is an OBSERVED actual, fall back to the
        // actual value so the actual-line label survives (its value is real even though no forecast was
        // loaded) — this is the fix for "all actual labels vanish on a partial-forecast render". Where
        // the forecast is finite we keep it unchanged, so normal renders behave exactly as before.
        val effectiveTemps = labelTemps.indices.map { i ->
            val forecast = labelTemps[i]
            if (!forecast.isNaN()) forecast
            else actualLabelTemps[i].let { actual -> if (hours[i].isActual && !actual.isNaN()) actual else forecast }
        }
        // Drop only candidates with no usable value at all (NaN forecast AND not an observed actual) —
        // e.g. a START/END boundary anchor that landed in a forecast gap.
        val deduplicatedIndices = deduplicateAnchors(potentialAnchors, labelTemps, actualLabelTemps)
            .filter { !effectiveTemps[it].isNaN() }
            .toSet()
        Log.v(TAG, "Deduplicated: $deduplicatedIndices")
        val explicitAnchors = deduplicatedIndices.filter { idx ->
            potentialAnchors.any { it.first == idx && it.second != TemperatureRole.LOCAL }
        }.toSet()
        Log.v(TAG, "Explicit: $explicitAnchors")

        // Actual-series anchors display the OBSERVED value, not the forecast value this thinning
        // compares on. They must stay drawn but must not declutter a nearby forecast/LOCAL extreme
        // (forecast vs actual are different series the user compares side by side). resolveExtremaRole's
        // priority order naturally excludes indices that are also a forecast global extreme.
        val actualDisplayingAnchors = deduplicatedIndices.filter {
            resolveExtremaRole(it, extrema, hours) in ACTUAL_DISPLAY_ROLES
        }.toSet()

        val filteredIndices = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = effectiveTemps,
            candidates = deduplicatedIndices.toList(),
            globalMaxIdx = extrema.dailyHighIndex,
            globalMinIdx = extrema.dailyLowIndex,
            maxCandidates = MAX_TEMP_LABEL_CANDIDATES,
            diffThresholds = DENSE_TEMP_DIFF_THRESHOLDS,
            valueFunction = { it.roundToInt() },
            logTag = TAG,
            protectedIndices = deduplicatedIndices.filter { it in extrema.significantLocalExtrema && it > effectiveActualEndIndex }.toSet(),
            immovableIndices = explicitAnchors,
            nonAbsorbingAnchors = actualDisplayingAnchors,
        )
        Log.v(TAG, "Filtered: $filteredIndices")

        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = effectiveTemps,
            candidates = filteredIndices,
            globalMaxIdx = extrema.dailyHighIndex,
            globalMinIdx = extrema.dailyLowIndex,
            valueFunction = { it.roundToInt() },
            nearbyWindow = min(5, (hours.lastIndex / 3).coerceAtLeast(1))
        )

        val finalIndices =
            if (numColumns >= 5 && filteredIndices.size == 2 && filteredIndices.containsAll(listOf(0, hours.lastIndex))) {
                val midIndex = hours.lastIndex / 2
                if (midIndex != 0 && midIndex != hours.lastIndex) {
                    (filteredIndices + midIndex).sorted()
                } else {
                    filteredIndices
                }
            } else {
                filteredIndices
            }

        val specialCandidates = mutableListOf<TempLabelCandidate>()
        val suppressedIndices = mutableSetOf<Int>()
        for (idx in finalIndices.distinct()) {
            var role = resolveExtremaRole(idx, extrema, hours)

            val leftEdgeResult = checkLeftEdgeSuppression(idx, role, suppressLeftEdgeLabel)
            if (leftEdgeResult.suppressed) {
                if (role in LOGGED_SUPPRESSION_ROLES) {
                    Log.v(TAG, "LabelSuppressed: role=$role idx=$idx reason=LEFT_EDGE")
                }
                suppressedIndices.add(idx)
                continue
            }

            val fetchResult = checkFetchDotSuppression(idx, role, extrema, observedAt, hours)
            if (fetchResult.suppressed) {
                if (role in LOGGED_SUPPRESSION_ROLES) {
                    Log.v(TAG, "LabelSuppressed: role=$role idx=$idx reason=FETCH_DOT")
                }
                suppressedIndices.add(idx)
                continue
            }
            fetchResult.overriddenRole?.let { role = it }

            if (checkRedundantPairSuppression(idx, role, extrema, suppressedIndices, labelTemps, actualLabelTemps, boundaryRedundancyWindow, hours, widthPx)) {
                if (role in LOGGED_SUPPRESSION_ROLES) {
                    Log.v(TAG, "LabelSuppressed: role=$role idx=$idx reason=REDUNDANT")
                }
                suppressedIndices.add(idx)
                continue
            }

            if (checkTransitionBoundarySuppression(idx, role, effectiveActualEndIndex, transitionX, hours)) {
                if (role in LOGGED_SUPPRESSION_ROLES) {
                    Log.v(TAG, "LabelSuppressed: role=$role idx=$idx reason=TRANSITION")
                }
                suppressedIndices.add(idx)
                continue
            }

            val isActualRole = role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.ACTUAL_END
            val forceForecast = role in listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.LOCAL, TemperatureRole.START, TemperatureRole.END)
            val temps = if (isActualRole) actualLabelTemps else labelTemps

            if (role in LOGGED_SUPPRESSION_ROLES) {
                logLabelDecision("LabelAccepted", role, idx, temps[idx], hours, reason = "EXTREMA", provenance = provenanceFor(role, isMidpoint = false))
            }
            specialCandidates.add(TempLabelCandidate(idx, role, temps, hours[idx].temperature, forceForecast))
        }

        addCoincidentActuals(specialCandidates, suppressedIndices, extrema.actualDailyHighIndices, TemperatureRole.ACTUAL_HIGH, FORECAST_HIGH_ROLES, hours, labelTemps, actualLabelTemps, "COINCIDENT_WITH_FORECAST_HIGH")
        addCoincidentActuals(specialCandidates, suppressedIndices, extrema.actualDailyLowIndices, TemperatureRole.ACTUAL_LOW, FORECAST_LOW_ROLES, hours, labelTemps, actualLabelTemps, "COINCIDENT_WITH_FORECAST_LOW")
        addForecastMidpointLabel(specialCandidates, effectiveActualEndIndex, hours, labelTemps)

        return specialCandidates
    }

    // Minimum forecast-region length (in indices ≈ hours) before its bare middle is worth a label.
    // 3 means a region of ≥4 points (e.g. a 3-hour forecast on a tight zoom) still gets a midpoint;
    // smaller regions have no meaningful interior point distinct from the endpoints.
    private const val MIN_FORECAST_MIDPOINT_SPAN = 3

    // A monotonic forecast (e.g. a steady overnight decline) has no interior extremum, so the only
    // label the engine emits in the future region is END — leaving the forecast line's middle bare.
    // When the future region [transition .. lastIndex] carries no label strictly inside it, drop a
    // single forecast-colored value label at its midpoint so the line always has a readable
    // reference. No-op when the region is short or already has an interior label.
    //
    // Scoped to a genuine forecast sub-region: requires a transition boundary
    // (effectiveActualEndIndex within the data). The whole-graph "only endpoints labeled" case is
    // governed separately by the numColumns>=5 midpoint rule above, which a narrow widget opts out
    // of — so this must not fire there.
    private fun addForecastMidpointLabel(
        specialCandidates: MutableList<TempLabelCandidate>,
        effectiveActualEndIndex: Int,
        hours: List<HourData>,
        labelTemps: List<Float>,
    ) {
        val lastIndex = hours.lastIndex
        val futureStart = effectiveActualEndIndex
        if (futureStart !in 0 until lastIndex) return
        if (lastIndex - futureStart < MIN_FORECAST_MIDPOINT_SPAN) return

        // Any label strictly between the forecast boundary and the END endpoint already covers it.
        val hasInteriorLabel = specialCandidates.any { it.index in (futureStart + 1) until lastIndex }
        if (hasInteriorLabel) return

        val mid = (futureStart + lastIndex) / 2
        if (mid <= futureStart || mid >= lastIndex) return
        if (specialCandidates.any { it.index == mid }) return
        if (mid !in labelTemps.indices) return

        // Don't repeat a value the forecast line already shows. The midpoint exists to give a NEW
        // readable reference for an otherwise-bare region; on a flat plateau its value equals the
        // global HIGH/LOW (or a region-boundary label) already on screen, so it would render as a
        // duplicate number (the Samsung "two 88°" bug). Not distance-gated: a duplicate anywhere on
        // the forecast line defeats the purpose of a reference label, however far away it sits.
        val midText = formatTemp(labelTemps[mid])
        val alreadyOnForecastLine = specialCandidates.any { c ->
            c.role in FORECAST_VALUE_ROLES &&
                c.index in labelTemps.indices &&
                formatTemp(labelTemps[c.index]) == midText
        }
        if (alreadyOnForecastLine) {
            logLabelDecision("MidpointSuppressed", TemperatureRole.LOCAL, mid, labelTemps[mid], hours, reason = "DUPLICATE_FORECAST_VALUE", provenance = provenanceFor(TemperatureRole.LOCAL, isMidpoint = true), extra = "duplicatesText=$midText")
            return
        }

        logLabelDecision("LabelAccepted", TemperatureRole.LOCAL, mid, labelTemps[mid], hours, reason = "FORECAST_MIDPOINT", provenance = provenanceFor(TemperatureRole.LOCAL, isMidpoint = true), extra = "futureStart=$futureStart lastIndex=$lastIndex")
        specialCandidates.add(
            TempLabelCandidate(mid, TemperatureRole.LOCAL, labelTemps, hours[mid].temperature, forceForecastSeries = true)
        )
    }

    // The candidate pipeline above is index-keyed: each hour yields a single label, and when an
    // observed extreme lands on the SAME hour as the global daily high/low, resolveExtremaRole
    // returns the forecast-valued HIGH/LOW first (those cases precede the actual-membership cases)
    // and the observed peak/valley goes unlabeled. Add a distinct ACTUAL_HIGH/ACTUAL_LOW at that
    // hour when the observed value differs enough to read differently, so forecast and actual are
    // both labeled (placement anchors the actual label to the observed point and stacks the two by
    // value). No-op when the actual extreme already has its own (distinct-index) label or when the
    // two values round to the same text. Iterates the per-day extrema so every day's coincident
    // case is covered, not just the global one.
    // Roles whose label shows the OBSERVED (actual) value rather than the forecast value.
    private val ACTUAL_DISPLAY_ROLES = setOf(
        TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_END,
    )

    // Roles whose label text is drawn from the forecast series (labelTemps), used to detect a
    // synthetic midpoint that merely repeats a value already shown on the forecast line. Excludes the
    // ACTUAL_* roles, which read actualLabelTemps (a different, differently-colored series).
    private val FORECAST_VALUE_ROLES = setOf(
        TemperatureRole.HIGH, TemperatureRole.LOW,
        TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
        TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
        TemperatureRole.START, TemperatureRole.END, TemperatureRole.LOCAL,
    )

    private val FORECAST_HIGH_ROLES = setOf(
        TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH,
    )
    private val FORECAST_LOW_ROLES = setOf(
        TemperatureRole.LOW, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW,
    )

    private fun addCoincidentActuals(
        specialCandidates: MutableList<TempLabelCandidate>,
        suppressedIndices: Set<Int>,
        actualIndices: List<Int>,
        actualRole: TemperatureRole,
        forecastRoles: Set<TemperatureRole>,
        hours: List<HourData>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
        reason: String,
    ) {
        for (idx in actualIndices) {
            if (idx < 0 || idx in suppressedIndices) continue
            if (idx !in actualLabelTemps.indices || idx !in labelTemps.indices) continue
            if (specialCandidates.any { it.index == idx && it.role == actualRole }) continue
            if (specialCandidates.none { it.index == idx && it.role in forecastRoles }) continue

            val actualVal = actualLabelTemps[idx]
            val forecastVal = labelTemps[idx]
            if (formatTemp(actualVal) == formatTemp(forecastVal)) continue

            logLabelDecision("LabelAccepted", actualRole, idx, actualVal, hours, reason = reason, provenance = provenanceFor(actualRole, isMidpoint = false))
            specialCandidates.add(
                TempLabelCandidate(idx, actualRole, actualLabelTemps, hours[idx].temperature, forceForecastSeries = false)
            )
        }
    }

    private fun resolveExtremaRole(
        idx: Int,
        extrema: TemperatureExtrema.ExtremaIndices,
        hours: List<HourData>,
    ): TemperatureRole = when (idx) {
        extrema.dailyHighIndex -> TemperatureRole.HIGH
        extrema.dailyLowIndex -> TemperatureRole.LOW
        // Actual extrema win over the START/END boundary roles: when the observed high/low lands on
        // the right-edge (NOW) or left-edge index, the label must show the observed value rather than
        // the forecast endpoint. Kept BELOW HIGH/LOW so forecast global extrema still drive the
        // dual-label injection in addCoincidentActuals.
        in extrema.actualDailyHighIndices -> TemperatureRole.ACTUAL_HIGH
        in extrema.actualDailyLowIndices -> TemperatureRole.ACTUAL_LOW
        0 -> TemperatureRole.START
        hours.lastIndex -> TemperatureRole.END
        extrema.forecastHighIndex -> TemperatureRole.FORECAST_HIGH
        extrema.forecastLowIndex -> TemperatureRole.FORECAST_LOW
        extrema.pastForecastHighIndex -> TemperatureRole.PAST_FORECAST_HIGH
        extrema.pastForecastLowIndex -> TemperatureRole.PAST_FORECAST_LOW
        extrema.actualEndIndex -> TemperatureRole.ACTUAL_END
        else -> TemperatureRole.LOCAL
    }

    private fun buildPotentialAnchors(
        extrema: TemperatureExtrema.ExtremaIndices,
        hoursCount: Int,
    ): MutableList<Pair<Int, TemperatureRole>> {
        val anchors = mutableListOf<Pair<Int, TemperatureRole>>()
        if (extrema.dailyHighIndex >= 0) anchors.add(extrema.dailyHighIndex to TemperatureRole.HIGH)
        if (extrema.dailyLowIndex >= 0) anchors.add(extrema.dailyLowIndex to TemperatureRole.LOW)
        extrema.actualDailyHighIndices.forEach { if (it >= 0) anchors.add(it to TemperatureRole.ACTUAL_HIGH) }
        extrema.actualDailyLowIndices.forEach { if (it >= 0) anchors.add(it to TemperatureRole.ACTUAL_LOW) }
        if (extrema.forecastHighIndex >= 0) anchors.add(extrema.forecastHighIndex to TemperatureRole.FORECAST_HIGH)
        if (extrema.forecastLowIndex >= 0) anchors.add(extrema.forecastLowIndex to TemperatureRole.FORECAST_LOW)
        if (extrema.pastForecastHighIndex >= 0) anchors.add(extrema.pastForecastHighIndex to TemperatureRole.PAST_FORECAST_HIGH)
        if (extrema.pastForecastLowIndex >= 0) anchors.add(extrema.pastForecastLowIndex to TemperatureRole.PAST_FORECAST_LOW)
        if (extrema.actualEndIndex >= 0) anchors.add(extrema.actualEndIndex to TemperatureRole.ACTUAL_END)
        anchors.add(0 to TemperatureRole.START)
        if (hoursCount > 1) anchors.add(hoursCount - 1 to TemperatureRole.END)
        return anchors
    }

    private fun deduplicateAnchors(
        potentialAnchors: List<Pair<Int, TemperatureRole>>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
    ): Set<Int> {
        // Actual extrema rank above START/END so a coincident-value boundary anchor cannot evict a
        // nearby actual high/low from a shared value-slot. Mirrors resolveExtremaRole's ordering;
        // kept below HIGH/LOW to preserve the forecast-extreme dual-label path.
        val rolePriority = listOf(
            TemperatureRole.HIGH, TemperatureRole.LOW,
            TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW,
            TemperatureRole.START, TemperatureRole.END, TemperatureRole.ACTUAL_END,
            TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
            TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
            TemperatureRole.LOCAL
        )
        val slotToAnchor = mutableMapOf<Triple<String, Int, Int>, Int>()
        for ((idx, role) in potentialAnchors) {
            val isActualRole = role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.ACTUAL_END
            val temps = if (isActualRole) actualLabelTemps else labelTemps
            val v = temps[idx]
            val formattedValue = formatTemp(v)
            var first = idx; var last = idx
            while (first > 0 && temps[first - 1] == v) first--
            while (last < temps.lastIndex && temps[last + 1] == v) last++
            val slotKey = Triple(formattedValue, first, last)
            val existingIdx = slotToAnchor[slotKey]
            if (existingIdx == null) {
                slotToAnchor[slotKey] = idx
            } else {
                val existingRole = potentialAnchors.find { it.first == existingIdx }?.second ?: TemperatureRole.LOCAL
                val existingPriority = rolePriority.indexOf(existingRole).let { if (it == -1) Int.MAX_VALUE else it }
                val currentPriority = rolePriority.indexOf(role).let { if (it == -1) Int.MAX_VALUE else it }
                if (currentPriority < existingPriority) {
                    slotToAnchor[slotKey] = idx
                }
            }
        }
        return slotToAnchor.values.toSet()
    }

    private fun checkLeftEdgeSuppression(
        idx: Int,
        role: TemperatureRole,
        suppressLeftEdgeLabel: Boolean,
    ): SuppressionResult {
        // An actual high/low keeps its own label at the left edge: an observed extreme at the start of
        // the observed data (e.g. the coldest point sits at midnight in the 24h day view) is a real
        // boundary value the user wants to see, mirroring the right-edge exemptions in
        // checkFetchDotSuppression / checkEndpointSuppression.
        if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW) {
            return SuppressionResult(false)
        }
        val isBoundary = role == TemperatureRole.START || role == TemperatureRole.END
        if (idx == 0 && suppressLeftEdgeLabel && !isBoundary) {
            return SuppressionResult(true)
        }
        return SuppressionResult(false)
    }

    private fun checkFetchDotSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: TemperatureExtrema.ExtremaIndices,
        observedAt: Long?,
        hours: List<HourData>,
    ): SuppressionResult {
        if (idx != extrema.fetchIdx || observedAt == null) return SuppressionResult(false)
        // An actual high/low keeps its own label even when the fetch dot lands on it: the observed
        // extreme value is the whole point of the label, so the dot must not relabel it as a START/END
        // boundary (which shows the forecast endpoint value) nor suppress it. Mirrors the HIGH/LOW
        // exemption below; without this, a curve still cooling at NOW loses its absolute-low label.
        if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW) {
            return SuppressionResult(false)
        }
        if (idx == 0 || idx == hours.lastIndex) {
            return SuppressionResult(false, overriddenRole = if (idx == 0) TemperatureRole.START else TemperatureRole.END)
        }
        if (role in listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW)) {
            return SuppressionResult(false)
        }
        return SuppressionResult(true)
    }

    private fun checkRedundantPairSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: TemperatureExtrema.ExtremaIndices,
        suppressedIndices: Set<Int>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
        boundaryWindow: Int,
        hours: List<HourData>,
        widthPx: Int,
    ): Boolean {
        val redundantValueThreshold = 2f
        // Legacy index window for same-semantic extrema pairs (forecast vs actual high/low): these
        // represent the same quantity, so a slightly wider, zoom-independent window is appropriate.
        val extremaWindow = min(8, labelTemps.lastIndex / 5)

        return when (role) {
            // The observed high is always worth its own label, mirroring ACTUAL_LOW below. Even
            // when the observed peak lands within a degree of the forecast/daily high, the two are
            // distinct data points the user wants to compare side by side, so never treat the actual
            // high as redundant — placement stacks/orders the two nearby labels by value instead.
            TemperatureRole.ACTUAL_HIGH -> false
            // The observed low is always worth its own label. It only resolves at an index distinct
            // from the daily low (when the global min IS an actual point, resolveExtremaRole returns
            // LOW first), so never treat it as redundant against a nearby forecast/daily low — the
            // two are stacked and ordered by value at placement time instead.
            TemperatureRole.ACTUAL_LOW -> false
            // Guard the actual index: with no actual data in view (forecast-only widget, or panned
            // into a forecast gap) actualHighIndex/actualLowIndex is -1, so there is nothing for the
            // forecast high/low to be redundant against — keep the label (and avoid an OOB access).
            TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH ->
                extrema.actualHighIndex >= 0 && isRedundantNear(idx, role, extrema.actualHighIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualHighIndex], extremaWindow, redundantValueThreshold, "ACTUAL_HIGH")
            TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW ->
                extrema.actualLowIndex >= 0 && isRedundantNear(idx, role, extrema.actualLowIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualLowIndex], extremaWindow, redundantValueThreshold, "ACTUAL_LOW")
            // Edge (boundary) labels are positional anchors, so "nearby" is a visual pixel budget, not
            // an index window. In the densely-sampled observed region near an edge many indices collapse
            // into a few pixels, so a START at idx 0 can be pixel-adjacent to a HIGH/ACTUAL_HIGH 30
            // indices away (a 3-day view packs idx 0..30 into ~5px). Measure the gap from real
            // timestamps (pixelGapByTime) when geometry is known; fall back to the legacy index window
            // for geometry-less callers (unit tests). Targets include the global forecast/actual extrema
            // AND the per-day actual extrema — the per-day observed high sitting beside the edge is what
            // makes the forecast boundary value redundant (the global actual high may be far to the right).
            TemperatureRole.START, TemperatureRole.END -> {
                // The GLOBAL daily extrema (the single most important forecast labels) get the looser
                // displayed-value tolerance: a START reading 73 beside the daily HIGH reading 75 is
                // redundant. SECONDARY forecast extrema (forecast-region / past-forecast high/low) keep
                // the strict raw < 2 gate, so e.g. an END reading 57 beside a forecast LOW of 55 — a
                // distinct local valley, not THE daily low — is still its own boundary label.
                val dailyTargets = listOf(extrema.dailyHighIndex, extrema.dailyLowIndex)
                val secondaryForecastTargets = listOf(
                    extrema.forecastHighIndex, extrema.forecastLowIndex,
                    extrema.pastForecastHighIndex, extrema.pastForecastLowIndex,
                )
                val actualTargets = listOf(extrema.actualHighIndex, extrema.actualLowIndex) +
                    extrema.actualDailyHighIndices + extrema.actualDailyLowIndices
                fun isTarget(tIdx: Int): Boolean = tIdx >= 0 && tIdx != idx && tIdx !in suppressedIndices
                fun nearEnough(tIdx: Int): Boolean =
                    if (widthPx > 0) pixelGapByTime(hours, idx, tIdx, widthPx) <= REDUNDANT_PAIR_PX
                    else abs(idx - tIdx) <= boundaryWindow
                dailyTargets.any { tIdx ->
                    isTarget(tIdx) && nearEnough(tIdx) &&
                        abs(labelTemps[idx].roundToInt() - labelTemps[tIdx].roundToInt()) <= SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES
                } || secondaryForecastTargets.any { tIdx ->
                    isTarget(tIdx) && nearEnough(tIdx) &&
                        abs(labelTemps[idx] - labelTemps[tIdx]) < redundantValueThreshold
                } || actualTargets.any { tIdx ->
                    isTarget(tIdx) && nearEnough(tIdx) &&
                        abs(labelTemps[idx] - actualLabelTemps[tIdx]) < redundantValueThreshold
                }
            }
            TemperatureRole.LOCAL, TemperatureRole.ACTUAL_END -> {
                val forecastCandidates = listOf(
                    extrema.dailyHighIndex, extrema.dailyLowIndex,
                    extrema.forecastHighIndex, extrema.forecastLowIndex,
                    extrema.pastForecastHighIndex, extrema.pastForecastLowIndex,
                )
                val actualCandidates = listOf(
                    extrema.actualHighIndex, extrema.actualLowIndex,
                )
                forecastCandidates.any { tIdx ->
                    tIdx >= 0 && tIdx != idx && tIdx !in suppressedIndices &&
                        abs(idx - tIdx) <= boundaryWindow &&
                        abs(labelTemps[idx] - labelTemps[tIdx]) < redundantValueThreshold
                } || actualCandidates.any { tIdx ->
                    tIdx >= 0 && tIdx != idx && tIdx !in suppressedIndices &&
                        abs(idx - tIdx) <= boundaryWindow &&
                        abs(labelTemps[idx] - actualLabelTemps[tIdx]) < redundantValueThreshold
                }
            }
            else -> false
        }
    }

    private fun isRedundantNear(
        idx: Int,
        role: TemperatureRole,
        targetIdx: Int,
        suppressedIndices: Set<Int>,
        currentVal: Float,
        targetVal: Float,
        window: Int,
        threshold: Float,
        reasonSuffix: String
    ): Boolean {
        if (targetIdx >= 0 && targetIdx !in suppressedIndices && abs(idx - targetIdx) <= window) {
            if (abs(currentVal - targetVal) < threshold) {
                return true
            }
        }
        return false
    }

    private fun checkTransitionBoundarySuppression(
        idx: Int,
        role: TemperatureRole,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        hours: List<HourData>,
    ): Boolean {
        if (role !in listOf(TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW)) return false
        if (transitionX == null) return false
        val boundaryIdx = effectiveActualEndIndex
        val transitionWindow = min(3, hours.lastIndex / 20)
        if (boundaryIdx >= 0 && abs(idx - boundaryIdx) <= transitionWindow) {
            return true
        }
        return false
    }

    fun sortLabelCandidates(candidates: MutableList<TempLabelCandidate>) {
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

    fun resolveCandidateGeometry(
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

        val label = formatTemp(temps[idx]) + "°"
        val textWidth = metrics.width(label, isFuture)
        val clampedX = sx.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)

        if (fetchDotX != null && lastObservedTemp != null && candidate.role !in setOf(TemperatureRole.START, TemperatureRole.END)) {
            val fetchDotLabel = formatTemp(lastObservedTemp) + "°"
            val dist = abs(clampedX - fetchDotX)
            if (label == fetchDotLabel && dist < 12f * density) {
                return null
            }
        }

        val leftVal = findPrevDifferent(temps, idx)
        val rightVal = findNextDifferent(temps, idx)
        val isValley = candidate.role in listOf(TemperatureRole.LOW, TemperatureRole.FORECAST_LOW, TemperatureRole.ACTUAL_LOW, TemperatureRole.PAST_FORECAST_LOW) || 
            (candidate.role == TemperatureRole.LOCAL && temps[idx] < leftVal && temps[idx] < rightVal)
        val isEssential = candidate.role in ESSENTIAL_LABEL_ROLES

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
            clampedX = clampedX
        )
    }

    private fun centerOfRun(
        idx: Int,
        temps: List<Float>,
        forceForecast: Boolean,
        original: List<Pair<Float, Float>>,
        forecast: List<Pair<Float, Float>>,
        transitionX: Float?
    ): Pair<Float, Float> {
        val v = temps[idx]; var first = idx; var last = idx
        while (first > 0 && abs(temps[first - 1] - v) < 0.01f) first--
        while (last < temps.lastIndex && abs(temps[last + 1] - v) < 0.01f) last++
        val points = if (forceForecast || (original.getOrNull(idx)?.first ?: 0f) > (transitionX ?: -1f)) forecast else original
        val fPoint = points.getOrNull(first) ?: (0f to 0f)
        val lPoint = points.getOrNull(last) ?: (0f to 0f)
        return (fPoint.first + lPoint.first) / 2f to (fPoint.second + lPoint.second) / 2f
    }
}
