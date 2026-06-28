package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TemperatureExtrema {
    private const val TAG = "TempExtrema"

    // How much warmer the rest of "today" must be forecast to get before the current (incomplete)
    // day's observed maximum is treated as not-yet-the-daily-high (so it isn't labeled as such). Set
    // above a normal forecast-vs-actual peak gap (a few degrees, which still gets both labels) so
    // only a clearly-unreached high — a morning bump well below the afternoon forecast — is dropped.
    private const val INCOMPLETE_DAY_HIGH_MARGIN_DEGREES = 5f

    // How far below BOTH flanking daily highs the trough between them must dip before we inject it as
    // a diurnal (overnight) actual low that the per-calendar-day pass missed. This is the gate that
    // separates a genuine overnight valley (deep — inject it) from a shallow same-peak shoulder (a
    // peak split across midnight — don't inject; let the shoulder-drop collapse it as before). A real
    // night dips many degrees; a split-peak shoulder dips < ~2°.
    private const val INTER_PEAK_LOW_MIN_PROMINENCE_DEGREES = 3f

    data class ExtremaIndices(
        val labelTemps: List<Float>,
        val actualLabelTemps: List<Float>,
        val dailyHighIndex: Int,
        val dailyLowIndex: Int,
        val actualHighIndex: Int,
        val actualLowIndex: Int,
        val actualDailyHighIndices: List<Int>,
        val actualDailyLowIndices: List<Int>,
        val forecastHighIndex: Int,
        val forecastLowIndex: Int,
        val pastForecastHighIndex: Int,
        val pastForecastLowIndex: Int,
        val actualEndIndex: Int,
        val significantLocalExtrema: List<Int>,
        val fetchIdx: Int,
    )

    fun compute(
        hours: List<HourData>,
        transitionX: Float?,
        effectiveActualEndIndex: Int,
        fetchTime: LocalDateTime?,
        prominenceThreshold: Float,
    ): ExtremaIndices {
        val labelTemps = hours.map { it.temperature }
        val actualLabelTemps = hours.map { h ->
            if (h.isActual) h.actualTemperature ?: h.temperature else h.temperature
        }
        // A graph window can extend past the loaded forecast horizon, leaving trailing hours with a
        // Float.NaN forecast temp (ActualTemperatureSeriesBuilder fills missing forecasts with NaN).
        // Float.compareTo ranks NaN as the LARGEST value, so a naive maxByOrNull would pick a NaN index
        // as the daily high — and that index later flows into roundToInt(), which throws
        // "Cannot round NaN value" and aborts the whole graph render (blank "Loading..." widget). Every
        // extrema index below is therefore chosen only among finite temps.
        val finiteLabelIndices = labelTemps.indices.filter { !labelTemps[it].isNaN() }
        val dailyHighIndex = finiteLabelIndices.maxByOrNull { labelTemps[it] } ?: -1
        val dailyLowIndex = finiteLabelIndices.minByOrNull { labelTemps[it] } ?: -1

        val actualEndIndex = if (transitionX != null) effectiveActualEndIndex else hours.lastIndex
        // Only points that genuinely have observed/carried actual data — NOT forecast-only points in
        // a leading gap. When the graph is panned into history older than the oldest observation, the
        // left of the window is forecast-only (isActual=false); without this gate those points fall
        // back to their forecast temp (see actualLabelTemps) and a gap day's forecast peak gets tagged
        // as that day's "actual high", drawing a pink ACTUAL label where no actual line exists. This
        // matches the actual-line draw gate (which also keys off isActual), keeping label and line
        // consistent. In the normal (un-panned) view every past point is actual/carried, so no change.
        val actualIndices = (0..actualEndIndex).filter { it in actualLabelTemps.indices && hours[it].isActual }
        
        Log.v(TAG, "ACTUAL_END_INDEX: $actualEndIndex transitionX=$transitionX")
        Log.v(TAG, "LABEL_TEMPS: $labelTemps")
        val nanIndices = labelTemps.mapIndexedNotNull { i, t -> if (t.isNaN()) i else null }
        if (nanIndices.isNotEmpty()) {
            Log.w(TAG, "NAN_TEMP_INDICES: $nanIndices hours=${nanIndices.map { hours[it].dateTime }}")
        }
        
        val actualHighIndex = actualIndices.maxByOrNull { actualLabelTemps[it] } ?: -1
        val actualLowIndex = actualIndices.minByOrNull { actualLabelTemps[it] } ?: -1

        // Per-day actual extrema: in a multi-day view the actual region spans several days, each
        // with its own valley/peak. The single global high/low above only labels the warmest/coldest
        // day, leaving every other day's actual extreme unlabeled (the forecast curve gets per-day
        // labels for free via significantLocalExtrema; this brings the actual series to parity).
        //
        // Keep only genuine turning points. A real actual high/low is a peak/valley with an OBSERVED
        // neighbour on BOTH sides confirming the turn. The first/last observed sample is just a window
        // edge: in a zoomed/panned view the real overnight valley (or afternoon peak) often lies
        // off-screen beyond the visible window, so the leftmost/rightmost sample is merely where the
        // window was cut — an edge value, not an extreme. We therefore never treat a boundary sample as
        // an actual extreme (this reverses the old symmetric edge exemption; see the
        // actual_low_left_edge_label / boundary_high_drop_left_edge history). Genuine multi-day slope
        // shoulders at interior day boundaries still require both neighbours here and are additionally
        // caught by the shoulder-drop walk below.
        val actualStartIndex = actualIndices.firstOrNull() ?: -1
        fun isActualLocalMin(i: Int): Boolean {
            if (actualStartIndex < 0 || i <= actualStartIndex || i >= actualEndIndex) return false
            return actualLabelTemps[i] <= actualLabelTemps[i - 1] &&
                actualLabelTemps[i] <= actualLabelTemps[i + 1]
        }
        fun isActualLocalMax(i: Int): Boolean {
            if (actualStartIndex < 0 || i <= actualStartIndex || i >= actualEndIndex) return false
            return actualLabelTemps[i] >= actualLabelTemps[i - 1] &&
                actualLabelTemps[i] >= actualLabelTemps[i + 1]
        }
        // The current (incomplete) day's observed maximum is NOT its daily high if the day hasn't
        // peaked yet — e.g. mid-morning "now" with the afternoon still ahead. Labeling that morning
        // bump as the day's actual high is misleading. Treat the day's high as "reached" only when the
        // forecast for the remainder of that same day does not exceed the observed max so far. Past
        // (completed) days are always real. Only applies when there is a NOW boundary (transitionX).
        val currentDay = if (transitionX != null && actualEndIndex in hours.indices) hours[actualEndIndex].dateTime.toLocalDate() else null
        fun dayHighReached(hi: Int): Boolean {
            val date = hours[hi].dateTime.toLocalDate()
            if (date != currentDay) return true
            val observedMax = actualLabelTemps[hi]
            val forecastRemainingMax = (actualEndIndex + 1..hours.lastIndex)
                .filter { it in labelTemps.indices && hours[it].dateTime.toLocalDate() == date }
                .maxOfOrNull { labelTemps[it] }
            // Not reached if the rest of today is forecast to climb meaningfully above the observed max.
            return forecastRemainingMax == null || forecastRemainingMax <= observedMax + INCOMPLETE_DAY_HIGH_MARGIN_DEGREES
        }

        val actualByDay = actualIndices.groupBy { hours[it].dateTime.toLocalDate() }
        // Each day's extreme is its ABSOLUTE max/min, kept only if that sample is an interior turning
        // point. Crucially we do NOT fall back to a lesser interior point when the absolute extreme is
        // at an edge: if the day's coldest/warmest sample sits at a window edge (now edge-gated out),
        // the day simply gets no label on that side — an interior shoulder that is less extreme than the
        // edge is NOT the real low/high and labeling it is misleading clutter ("an extreme is not an
        // extreme if the edge is more extreme").
        val rawDailyHighIndices = actualByDay.values.mapNotNull { d -> d.maxByOrNull { actualLabelTemps[it] } }.filter { isActualLocalMax(it) && dayHighReached(it) }.sorted()
        val perDayLowIndices = actualByDay.values.mapNotNull { d -> d.minByOrNull { actualLabelTemps[it] } }.filter { isActualLocalMin(it) }.sorted()
        // Per-day RAW actual-extrema trace. VERBOSE => logcat/console only, never persisted to app_logs
        // (the sparse-log boundary drops VERBOSE), so it is cheap to leave on permanently. This is the
        // FIRST place to look when a day's pink actual high/low label goes missing: it shows each
        // calendar day's chosen min/max index + temp + time and which predicate gates it
        // (localMax/dayHighReached for highs, localMin for lows), plus the rejected min's neighbours.
        // Classic failure it exposes: an overnight low that straddles midnight makes a day's calendar
        // minimum land on a still-descending shoulder near 23:55 (localMin=false), so that day gets no
        // low and the real trough is owned by the next calendar day.
        actualByDay.entries.sortedBy { it.key }.forEach { (date, idxs) ->
            val maxIdx = idxs.maxByOrNull { actualLabelTemps[it] }
            val minIdx = idxs.minByOrNull { actualLabelTemps[it] }
            Log.v(TAG, "PERDAY_RAW date=$date n=${idxs.size} range=[${idxs.minOrNull()}..${idxs.maxOrNull()}] " +
                "max(idx=$maxIdx t=${maxIdx?.let { hours[it].dateTime.toLocalTime() }} temp=${maxIdx?.let { actualLabelTemps[it] }} " +
                "localMax=${maxIdx?.let { isActualLocalMax(it) }} highReached=${maxIdx?.let { dayHighReached(it) }}) " +
                "min(idx=$minIdx t=${minIdx?.let { hours[it].dateTime.toLocalTime() }} temp=${minIdx?.let { actualLabelTemps[it] }} " +
                "localMin=${minIdx?.let { isActualLocalMin(it) }} " +
                "nbr[-1]=${minIdx?.let { if (it - 1 in actualLabelTemps.indices) actualLabelTemps[it - 1] else null }} " +
                "nbr[+1]=${minIdx?.let { if (it + 1 in actualLabelTemps.indices) actualLabelTemps[it + 1] else null }})")
        }
        Log.v(TAG, "PERDAY_RAW highs=$rawDailyHighIndices lows=$perDayLowIndices actualStart=$actualStartIndex actualEnd=$actualEndIndex")

        // Inject the diurnal trough BETWEEN consecutive daily highs when the per-calendar-day pass
        // missed it. A night low that straddles midnight makes its calendar day's minimum land on a
        // still-descending ~23:55 shoulder (localMin=false, visible in PERDAY_RAW), so that day yields
        // no low and the real trough is mis-owned by the next day. Two daily highs then sit adjacent
        // and the shoulder-drop below would wrongly collapse the cooler one as a "split peak". For each
        // high-pair with no per-day low between them, add the deepest OBSERVED point in the gap — but
        // ONLY when it is a genuine valley (>= INTER_PEAK_LOW_MIN_PROMINENCE_DEGREES below both flanking
        // highs). That prominence gate keeps a real overnight trough (deep) while still letting a shallow
        // same-peak shoulder fall through to the shoulder-drop, preserving existing behaviour.
        val interPeakLows = rawDailyHighIndices.zipWithNext().mapNotNull { (hiA, hiB) ->
            if (perDayLowIndices.any { it in (hiA + 1) until hiB }) return@mapNotNull null
            val troughIdx = actualIndices.filter { it in (hiA + 1) until hiB }
                .minByOrNull { actualLabelTemps[it] } ?: return@mapNotNull null
            val flankMin = minOf(actualLabelTemps[hiA], actualLabelTemps[hiB])
            if (isActualLocalMin(troughIdx) &&
                actualLabelTemps[troughIdx] <= flankMin - INTER_PEAK_LOW_MIN_PROMINENCE_DEGREES) troughIdx else null
        }
        val rawDailyLowIndices = (perDayLowIndices + interPeakLows).distinct().sorted()
        if (interPeakLows.isNotEmpty()) {
            Log.v(TAG, "INTER_PEAK_LOW_INJECTED idxs=$interPeakLows temps=${interPeakLows.map { actualLabelTemps[it] }} " +
                "t=${interPeakLows.map { hours[it].dateTime.toLocalTime() }}")
        }

        // Drop midnight-straddle "shoulder" extrema. A genuine diurnal cycle always separates two
        // successive actual lows with an actual high (and two highs with a low), so walking the
        // per-day extrema in index order, two SAME-TYPE neighbours with no opposite-type extreme
        // between them are one overnight valley/afternoon peak split across a calendar boundary:
        // one day's min/max landed on a slope shoulder at the day edge while the real turning point
        // is owned by the adjacent day. Keep only the genuinely deeper low / higher high. This is
        // robust to jagged observation data, where isActualLocalMin/Max (immediate-neighbour only)
        // is fooled by a 1-sample wiggle on the descent. See per_day_actual_extrema_labels memory.
        val shoulderDrops = mutableSetOf<Int>()
        val mergedExtrema = (rawDailyHighIndices.map { it to true } + rawDailyLowIndices.map { it to false })
            .sortedBy { it.first }
        var keptExtreme: Pair<Int, Boolean>? = null
        for (cur in mergedExtrema) {
            val kept = keptExtreme
            if (kept != null && kept.second == cur.second) {
                val keepCur = if (cur.second) actualLabelTemps[cur.first] >= actualLabelTemps[kept.first]
                              else actualLabelTemps[cur.first] <= actualLabelTemps[kept.first]
                if (keepCur) {
                    shoulderDrops.add(kept.first)
                    keptExtreme = cur
                } else {
                    shoulderDrops.add(cur.first)
                }
            } else {
                keptExtreme = cur
            }
        }
        val shoulderedHighIndices = rawDailyHighIndices.filterNot { it in shoulderDrops }
        // A partial edge day spanning ~1h can have an actual high and low that round to the same
        // displayed value (e.g. 63.91 / 63.88 -> both "63.9°"), which stacks two identical labels at
        // the graph edge. When a day's high and low render identically, keep the high and drop the
        // redundant low. See per_day_actual_extrema_labels memory.
        val highIdxByDay = shoulderedHighIndices.associateBy { hours[it].dateTime.toLocalDate() }
        val degenerateLowDrops = rawDailyLowIndices.filter { lowIdx ->
            val hiIdx = highIdxByDay[hours[lowIdx].dateTime.toLocalDate()] ?: return@filter false
            TemperatureLabelResolver.formatTemp(actualLabelTemps[hiIdx]) ==
                TemperatureLabelResolver.formatTemp(actualLabelTemps[lowIdx])
        }.toSet()
        val actualDailyLowIndices = rawDailyLowIndices.filterNot { it in shoulderDrops || it in degenerateLowDrops }
        // Boundary samples are no longer classified as actual highs (isActualLocalMax requires a
        // confirming neighbour on both sides), so a warm window-edge START can never reach this list —
        // the old left-boundary-high drop is unnecessary. A spurious edge "high" simply never exists.
        val actualDailyHighIndices = shoulderedHighIndices
        if (shoulderDrops.isNotEmpty()) {
            Log.v(TAG, "SHOULDER_DROPPED idxs=${shoulderDrops.sorted()} temps=${shoulderDrops.sorted().map { actualLabelTemps[it] }}")
        }
        if (degenerateLowDrops.isNotEmpty()) {
            Log.v(TAG, "DEGENERATE_DAY_LOW_DROPPED idxs=${degenerateLowDrops.sorted()} " +
                "temps=${degenerateLowDrops.sorted().map { actualLabelTemps[it] }}")
        }

        Log.v(TAG, "ACTUAL_EXTREMA highIdx=$actualHighIndex highTemp=${if (actualHighIndex >= 0) actualLabelTemps[actualHighIndex] else "N/A"} " +
                "lowIdx=$actualLowIndex lowTemp=${if (actualLowIndex >= 0) actualLabelTemps[actualLowIndex] else "N/A"} " +
                "actualIndicesRange=${actualIndices.firstOrNull()}..${actualIndices.lastOrNull()}")
        Log.v(TAG, "ACTUAL_DAILY highIdxs=$actualDailyHighIndices highTemps=${actualDailyHighIndices.map { actualLabelTemps[it] }} " +
                "lowIdxs=$actualDailyLowIndices lowTemps=${actualDailyLowIndices.map { actualLabelTemps[it] }}")

        val forecastStartIndex = if (transitionX != null) effectiveActualEndIndex else 0
        val forecastIndices = (forecastStartIndex..hours.lastIndex).filter { it in labelTemps.indices && !labelTemps[it].isNaN() }
        val forecastHighIndex = forecastIndices.maxByOrNull { labelTemps[it] } ?: -1
        val forecastLowIndex = forecastIndices.minByOrNull { labelTemps[it] } ?: -1

        val hasTransition = transitionX != null
        val pastForecastIndices = if (hasTransition) (0..actualEndIndex).filter { it in labelTemps.indices && !labelTemps[it].isNaN() } else emptyList()
        val pastForecastHighIndex = if (hasTransition) pastForecastIndices.maxByOrNull { labelTemps[it] } ?: -1 else -1
        val pastForecastLowIndex = if (hasTransition) pastForecastIndices.minByOrNull { labelTemps[it] } ?: -1 else -1

        if (forecastHighIndex >= 0 && forecastLowIndex >= 0) {
            val forecastDates = forecastIndices.map { hours[it].dateTime.toLocalDate() }.distinct()
            Log.v(TAG, "FORECAST_EXTREMA highIdx=$forecastHighIndex highTemp=${labelTemps[forecastHighIndex]} " +
                "lowIdx=$forecastLowIndex lowTemp=${labelTemps[forecastLowIndex]} " +
                "forecastDates=$forecastDates forecastRange=$forecastStartIndex..${hours.lastIndex}")
        }

        val localExtrema = findLocalExtremaIndices(labelTemps)
        val significantLocalExtrema = localExtrema.filter { index ->
            val prom = bilateralExtremaProminence(index, labelTemps, localExtrema)
            if (prom < prominenceThreshold) {
                Log.v(TAG, "EXTREMUM_REJECTED idx=$index temp=${labelTemps[index]} prominence=$prom threshold=$prominenceThreshold")
                false
            } else {
                Log.v(TAG, "SIGNIFICANT_EXTREMUM idx=$index temp=${labelTemps[index]} prominence=$prom")
                true
            }
        }

        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1

        return ExtremaIndices(
            labelTemps, actualLabelTemps,
            dailyHighIndex, dailyLowIndex,
            actualHighIndex, actualLowIndex,
            actualDailyHighIndices, actualDailyLowIndices,
            forecastHighIndex, forecastLowIndex,
            pastForecastHighIndex, pastForecastLowIndex,
            actualEndIndex,
            significantLocalExtrema, fetchIdx
        )
    }

    fun findLocalExtremaIndices(temps: List<Float>): List<Int> {
        val extrema = mutableListOf<Int>()
        if (temps.size < 3) return extrema
        var i = 1
        while (i < temps.size - 1) {
            val current = temps[i]; val prev = temps[i - 1]; val next = temps[i + 1]
            if ((current > prev && current > next) || (current < prev && current < next)) extrema.add(i)
            else if (current == next && current != prev) {
                var j = i + 1
                while (j < temps.size - 1 && temps[j] == current) j++
                if (j < temps.size && ((current > prev && current > temps[j]) || (current < prev && current < temps[j]))) extrema.add((i + j) / 2)
                i = j - 1
            }
            i++
        }
        return extrema
    }

    fun bilateralExtremaProminence(index: Int, temps: List<Float>, extrema: List<Int>): Float {
        val current = temps[index]; val extremaSet = extrema.toSet()
        fun maxDelta(step: Int): Float {
            var maxD = 0f; var cursor = index + step
            while (cursor in temps.indices) {
                maxD = max(maxD, abs(temps[cursor] - current))
                if (cursor != index + step && cursor in extremaSet) break
                cursor += step
            }
            return maxD
        }
        val left = maxDelta(-1); val right = maxDelta(1)
        return if (left == 0f || right == 0f) 0f else min(left, right)
    }
}
