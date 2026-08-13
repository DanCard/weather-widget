package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log
import java.time.Duration
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The candidate-collection pipeline: turns the raw [TemperatureExtrema.ExtremaIndices] into the
 * ordered list of [TempLabelCandidate]s the engine will place. It owns anchor building, value-slot
 * deduplication, the dense-thinning call, the four suppression passes (via [LabelSuppression]), and
 * the coincident/midpoint/turning-point enrichment. Extracted from [TemperatureLabelResolver] so
 * the resolver stays a thin facade.
 */
internal object LabelCandidateCollector {
    // Kept as the historical logcat tag: this object was extracted from TemperatureLabelResolver,
    // and the breadcrumbs were always grep'd under "TempLabelResolver" on-device.
    private const val TAG = "TempLabelResolver"
    private const val MAX_TEMP_LABEL_CANDIDATES = 6
    private val DENSE_TEMP_DIFF_THRESHOLDS = listOf(3, 4, 5)

    // Minimum forecast-region length (in indices ≈ hours) before its bare middle is worth a label.
    // 3 means a region of ≥4 points (e.g. a 3-hour forecast on a tight zoom) still gets a midpoint;
    // smaller regions have no meaningful interior point distinct from the endpoints.
    private const val MIN_FORECAST_MIDPOINT_SPAN = 3

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

    internal fun collect(
        hours: List<HourData>,
        extrema: TemperatureExtrema.ExtremaIndices,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        observedAt: Long?,
        numColumns: Int = 0,
        widthPx: Int = 0,
        useCelsius: Boolean,
    ): List<TempLabelCandidate> {
        val labelTemps = extrema.labelTemps
        val actualLabelTemps = extrema.actualLabelTemps

        // Boundary labels (START/END) are positional anchors, so "nearby" for them is a visual
        // (zoom-aware) pixel budget. Extrema-vs-extrema redundancy (forecast-vs-actual high/low) is
        // about the SAME semantic quantity and keeps the legacy index window inside the check.
        val boundaryRedundancyWindow = LabelGeometryResolver.computeRedundantPairWindow(hours, widthPx)
        Log.v(TAG, "RedundancyWindow: boundary=$boundaryRedundancyWindow widthPx=$widthPx hours=${hours.size}")

        val actualStartIndex = hours.indexOfFirst { it.isActual }
        val shortCrossMidnightActualSlice = actualStartIndex == 0 &&
            extrema.actualEndIndex in hours.indices &&
            hours[actualStartIndex].dateTime.toLocalDate() != hours[extrema.actualEndIndex].dateTime.toLocalDate() &&
            Duration.between(hours[actualStartIndex].dateTime, hours[extrema.actualEndIndex].dateTime) <= Duration.ofHours(3)
        val dailyActualHighLabelIndices = if (shortCrossMidnightActualSlice) {
            listOfNotNull(extrema.actualHighIndex.takeIf { it == 0 })
        } else {
            (extrema.actualDailyHighIndices + listOfNotNull(extrema.actualHighIndex.takeIf { it == 0 })).distinct()
        }
        val dailyActualLowLabelIndices = if (shortCrossMidnightActualSlice) {
            listOfNotNull(extrema.actualLowIndex.takeIf { it == extrema.actualEndIndex })
        } else {
            (extrema.actualDailyLowIndices + listOfNotNull(extrema.actualLowIndex.takeIf { it == extrema.actualEndIndex })).distinct()
        }
        // Daily actual extrema remain the primary labels. Only when a visible slice has no confirmed
        // actual high (or low) do prominent interior observed turns fill that semantic gap. This
        // prevents multi-day views that already have daily labels from accumulating extra pink noise.
        //
        // And it fills that gap ONCE. TemperatureExtrema.findProminentActualTurningPoints only applies
        // a per-reversal hysteresis (ACTUAL_TURN_REVERSAL_DEGREES, 0.75°F) with no cap, so a flat
        // afternoon plateau on the observed line returns every chatter turn that clears it: the
        // 2026-08-09 desktop window produced 3 highs and 2 lows inside 1.6°F over 105 minutes, all
        // piled on the same spot. The question this fallback answers is "where did the observed line
        // peak/bottom out in this slice?", which has one answer per side — so keep the most extreme
        // turn and drop the rest. (Multi-peak windows are the daily-extrema path's job, not this one.)
        val fallbackActualHighIndices =
            if (dailyActualHighLabelIndices.isEmpty()) {
                mostExtremeTurn(extrema.actualProminentHighIndices, actualLabelTemps, wantMax = true)
            } else {
                emptyList()
            }
        val fallbackActualLowIndices =
            if (dailyActualLowLabelIndices.isEmpty()) {
                mostExtremeTurn(extrema.actualProminentLowIndices, actualLabelTemps, wantMax = false)
            } else {
                emptyList()
            }
        val actualHighLabelIndices = dailyActualHighLabelIndices + fallbackActualHighIndices
        val actualLowLabelIndices = dailyActualLowLabelIndices + fallbackActualLowIndices

        val potentialAnchors = buildPotentialAnchors(extrema, hours)
        if (shortCrossMidnightActualSlice) {
            potentialAnchors.removeAll { (idx, role) ->
                (role == TemperatureRole.ACTUAL_HIGH && idx !in actualHighLabelIndices) ||
                    (role == TemperatureRole.ACTUAL_LOW && idx !in actualLowLabelIndices)
            }
        }
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
        val deduplicatedIndices = deduplicateAnchors(potentialAnchors, labelTemps, actualLabelTemps, useCelsius)
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
            TemperatureLabelResolver.resolveExtremaRole(it, extrema, hours) in TemperatureLabelResolver.ACTUAL_DISPLAY_ROLES
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
            var role = TemperatureLabelResolver.resolveExtremaRole(idx, extrema, hours)

            val leftEdgeResult = LabelSuppression.checkLeftEdgeSuppression(idx, role, suppressLeftEdgeLabel)
            if (leftEdgeResult.suppressed) {
                if (role in LOGGED_SUPPRESSION_ROLES) {
                    Log.v(TAG, "LabelSuppressed: role=$role idx=$idx reason=LEFT_EDGE")
                }
                suppressedIndices.add(idx)
                continue
            }

            val fetchResult = LabelSuppression.checkFetchDotSuppression(idx, role, extrema, observedAt, hours)
            if (fetchResult.suppressed) {
                if (role in LOGGED_SUPPRESSION_ROLES) {
                    Log.v(TAG, "LabelSuppressed: role=$role idx=$idx reason=FETCH_DOT")
                }
                suppressedIndices.add(idx)
                continue
            }
            fetchResult.overriddenRole?.let { role = it }

            if (LabelSuppression.checkRedundantPairSuppression(idx, role, extrema, suppressedIndices, labelTemps, actualLabelTemps, boundaryRedundancyWindow, hours, widthPx)) {
                if (role in LOGGED_SUPPRESSION_ROLES) {
                    Log.v(TAG, "LabelSuppressed: role=$role idx=$idx reason=REDUNDANT")
                }
                suppressedIndices.add(idx)
                continue
            }

            if (LabelSuppression.checkTransitionBoundarySuppression(idx, role, effectiveActualEndIndex, transitionX, hours)) {
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
                logLabelDecision("LabelAccepted", role, idx, temps[idx], hours, reason = "EXTREMA", provenance = provenanceFor(role, isMidpoint = false), useCelsius = useCelsius)
            }
            specialCandidates.add(TempLabelCandidate(idx, role, temps, hours[idx].temperature, forceForecast))
        }

        addActualTurningPointLabels(
            specialCandidates = specialCandidates,
            indices = fallbackActualHighIndices,
            role = TemperatureRole.ACTUAL_HIGH,
            hours = hours,
            actualLabelTemps = actualLabelTemps,
            useCelsius = useCelsius,
        )
        addActualTurningPointLabels(
            specialCandidates = specialCandidates,
            indices = fallbackActualLowIndices,
            role = TemperatureRole.ACTUAL_LOW,
            hours = hours,
            actualLabelTemps = actualLabelTemps,
            useCelsius = useCelsius,
        )

        addCoincidentActuals(
            specialCandidates,
            suppressedIndices,
            actualHighLabelIndices,
            TemperatureRole.ACTUAL_HIGH,
            TemperatureLabelResolver.FORECAST_HIGH_ROLES,
            hours,
            labelTemps,
            actualLabelTemps,
            "COINCIDENT_WITH_FORECAST_HIGH",
            useCelsius = useCelsius,
        )
        addCoincidentActuals(
            specialCandidates,
            suppressedIndices,
            actualLowLabelIndices,
            TemperatureRole.ACTUAL_LOW,
            TemperatureLabelResolver.FORECAST_LOW_ROLES,
            hours,
            labelTemps,
            actualLabelTemps,
            "COINCIDENT_WITH_FORECAST_LOW",
            useCelsius = useCelsius,
        )
        addForecastMidpointLabel(specialCandidates, effectiveActualEndIndex, hours, labelTemps, useCelsius = useCelsius)

        return specialCandidates
    }

    /**
     * The single most extreme of [indices] by [temps] — warmest when [wantMax], coldest otherwise —
     * as a 0-or-1 element list. Ties go to the earliest index so a plateau labels its onset, which is
     * both stable across renders and the point a reader is looking for.
     */
    private fun mostExtremeTurn(
        indices: List<Int>,
        temps: List<Float>,
        wantMax: Boolean,
    ): List<Int> {
        val usable = indices.filter { it in temps.indices && !temps[it].isNaN() }
        if (usable.isEmpty()) return emptyList()
        val best = usable.reduce { acc, i ->
            val better = if (wantMax) temps[i] > temps[acc] else temps[i] < temps[acc]
            if (better) i else acc
        }
        if (usable.size > 1) {
            Log.v(
                TAG,
                "ActualTurnThinning: kept=$best of ${usable.size} " +
                    "(${if (wantMax) "high" else "low"}s=${usable.map { "idx=$it temp=${temps[it]}" }})",
            )
        }
        return listOf(best)
    }

    private fun addActualTurningPointLabels(
        specialCandidates: MutableList<TempLabelCandidate>,
        indices: List<Int>,
        role: TemperatureRole,
        hours: List<HourData>,
        actualLabelTemps: List<Float>,
        useCelsius: Boolean,
    ) {
        for (idx in indices) {
            if (idx !in hours.indices || idx !in actualLabelTemps.indices) continue
            if (specialCandidates.any { it.index == idx && it.role == role }) continue
            logLabelDecision(
                action = "LabelAccepted",
                role = role,
                idx = idx,
                value = actualLabelTemps[idx],
                hours = hours,
                reason = "PROMINENT_ACTUAL_TURN",
                provenance = provenanceFor(role, isMidpoint = false),
                useCelsius = useCelsius,
            )
            specialCandidates.add(
                TempLabelCandidate(
                    index = idx,
                    role = role,
                    labelTemps = actualLabelTemps,
                    rawTemperature = hours[idx].temperature,
                    forceForecastSeries = false,
                ),
            )
        }
    }

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
        useCelsius: Boolean,
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

        val distToFutureStart = mid - futureStart
        if (distToFutureStart <= 2) {
            Log.v(TAG, "ForecastMidpointCloseToTransition: mid=$mid futureStart=$futureStart span=${lastIndex - futureStart} -- label may collide with NOW/fetchDot hard bounds and require leader line")
        }
        if (specialCandidates.any { it.index == mid }) return
        if (mid !in labelTemps.indices) return

        // Don't repeat a value the forecast line already shows. The midpoint exists to give a NEW
        // readable reference for an otherwise-bare region; on a flat plateau its value equals the
        // global HIGH/LOW (or a region-boundary label) already on screen, so it would render as a
        // duplicate number (the Samsung "two 88°" bug). Not distance-gated: a duplicate anywhere on
        // the forecast line defeats the purpose of a reference label, however far away it sits.
        val midText = TemperatureLabelResolver.formatTemp(labelTemps[mid], useCelsius)
        val alreadyOnForecastLine = specialCandidates.any { c ->
            c.role in TemperatureLabelResolver.FORECAST_VALUE_ROLES &&
                c.index in labelTemps.indices &&
                TemperatureLabelResolver.formatTemp(labelTemps[c.index], useCelsius) == midText
        }
        if (alreadyOnForecastLine) {
            logLabelDecision("MidpointSuppressed", TemperatureRole.LOCAL, mid, labelTemps[mid], hours, reason = "DUPLICATE_FORECAST_VALUE", provenance = provenanceFor(TemperatureRole.LOCAL, isMidpoint = true), extra = "duplicatesText=$midText", useCelsius = useCelsius)
            return
        }

        logLabelDecision("LabelAccepted", TemperatureRole.LOCAL, mid, labelTemps[mid], hours, reason = "FORECAST_MIDPOINT", provenance = provenanceFor(TemperatureRole.LOCAL, isMidpoint = true), extra = "futureStart=$futureStart lastIndex=$lastIndex", useCelsius = useCelsius)
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
        useCelsius: Boolean,
    ) {
        for (idx in actualIndices) {
            if (idx < 0 || idx in suppressedIndices) continue
            if (idx !in actualLabelTemps.indices || idx !in labelTemps.indices) continue
            if (specialCandidates.any { it.index == idx && it.role == actualRole }) continue
            if (specialCandidates.none { it.index == idx && it.role in forecastRoles }) continue

            val actualVal = actualLabelTemps[idx]
            val forecastVal = labelTemps[idx]
            if (TemperatureLabelResolver.formatTemp(actualVal, useCelsius) == TemperatureLabelResolver.formatTemp(forecastVal, useCelsius)) continue

            logLabelDecision("LabelAccepted", actualRole, idx, actualVal, hours, reason = reason, provenance = provenanceFor(actualRole, isMidpoint = false), useCelsius = useCelsius)
            specialCandidates.add(
                TempLabelCandidate(idx, actualRole, actualLabelTemps, hours[idx].temperature, forceForecastSeries = false)
            )
        }
    }

    private fun buildPotentialAnchors(
        extrema: TemperatureExtrema.ExtremaIndices,
        hours: List<HourData>,
    ): MutableList<Pair<Int, TemperatureRole>> {
        val anchors = mutableListOf<Pair<Int, TemperatureRole>>()
        if (extrema.dailyHighIndex >= 0) anchors.add(extrema.dailyHighIndex to TemperatureRole.HIGH)
        if (extrema.dailyLowIndex >= 0) anchors.add(extrema.dailyLowIndex to TemperatureRole.LOW)
        if (extrema.actualHighIndex == 0) {
            anchors.add(extrema.actualHighIndex to TemperatureRole.ACTUAL_HIGH)
        }
        if (extrema.actualLowIndex >= 0 && extrema.actualLowIndex == extrema.actualEndIndex) {
            anchors.add(extrema.actualLowIndex to TemperatureRole.ACTUAL_LOW)
        }
        extrema.actualDailyHighIndices.forEach { if (it >= 0) anchors.add(it to TemperatureRole.ACTUAL_HIGH) }
        extrema.actualDailyLowIndices.forEach { if (it >= 0) anchors.add(it to TemperatureRole.ACTUAL_LOW) }
        if (extrema.forecastHighIndex >= 0) anchors.add(extrema.forecastHighIndex to TemperatureRole.FORECAST_HIGH)
        if (extrema.forecastLowIndex >= 0) anchors.add(extrema.forecastLowIndex to TemperatureRole.FORECAST_LOW)
        if (extrema.pastForecastHighIndex >= 0) anchors.add(extrema.pastForecastHighIndex to TemperatureRole.PAST_FORECAST_HIGH)
        if (extrema.pastForecastLowIndex >= 0) anchors.add(extrema.pastForecastLowIndex to TemperatureRole.PAST_FORECAST_LOW)
        if (extrema.actualEndIndex >= 0) anchors.add(extrema.actualEndIndex to TemperatureRole.ACTUAL_END)
        anchors.add(0 to TemperatureRole.START)
        if (hours.size > 1) anchors.add(hours.lastIndex to TemperatureRole.END)
        return anchors
    }

    private fun deduplicateAnchors(
        potentialAnchors: List<Pair<Int, TemperatureRole>>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
        useCelsius: Boolean,
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
            val formattedValue = TemperatureLabelResolver.formatTemp(v, useCelsius)
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

    // Where a label's value came from — so a "what is this number / is it interpolated?" question is
    // answerable straight from the log, without re-deriving the dense series by hand:
    //   OBSERVED          = the measured/blended actual line (actualLabelTemps)
    //   SMOOTHED_FORECAST = the smoothed forecast curve at a real extremum/endpoint (labelTemps)
    //   SMOOTHED_MIDPOINT = a synthesized anchor dropped on a bare monotonic forecast stretch —
    //                       interpolated, NOT a data point (this is the one that reads e.g. "73.8°"
    //                       sitting between a 74° start and a 72° end). See addForecastMidpointLabel.
    private fun provenanceFor(role: TemperatureRole, isMidpoint: Boolean): String = when {
        isMidpoint -> "SMOOTHED_MIDPOINT"
        role in TemperatureLabelResolver.ACTUAL_DISPLAY_ROLES -> "OBSERVED"
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
        useCelsius: Boolean,
    ) {
        val t = hours.getOrNull(idx)?.dateTime?.toLocalTime()?.toString() ?: "?"
        // No degree glyph: the file log sink isn't UTF-8 and renders ° as '?'. The bare number still
        // greps against what's on screen (e.g. `grep 'displayed="73.8'`).
        Log.v(
            TAG,
            "$action: displayed=\"${TemperatureLabelResolver.formatTemp(value, useCelsius)}\" t=$t role=$role reason=$reason " +
                "provenance=$provenance val=$value idx=$idx" + if (extra.isEmpty()) "" else " $extra",
        )
    }
}
