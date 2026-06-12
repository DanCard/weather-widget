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
        val dailyHighIndex = labelTemps.indices.maxByOrNull { labelTemps[it] } ?: -1
        val dailyLowIndex = labelTemps.indices.minByOrNull { labelTemps[it] } ?: -1

        val actualEndIndex = if (transitionX != null) effectiveActualEndIndex else hours.lastIndex
        val actualIndices = (0..actualEndIndex).filter { it in actualLabelTemps.indices }
        
        Log.d(TAG, "ACTUAL_END_INDEX: $actualEndIndex transitionX=$transitionX")
        Log.d(TAG, "LABEL_TEMPS: $labelTemps")
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
        // Keep only genuine turning points: an overnight valley straddles midnight, so the day on the
        // "wrong" side of the boundary has its min/max land on a still-monotonic slope point at the
        // day edge (e.g. Tue's "low" is just the descent into Wed's post-midnight valley). Labeling
        // that slope point is redundant next to the real adjacent-day extreme, so drop it.
        // The actual-region's own end (the observation cutoff / NOW) is exempt: an extreme there is a
        // real observed boundary value (e.g. temp still climbing to NOW), not a midnight artifact, so
        // we only require the neighbor that exists within the observed data.
        fun isActualLocalMin(i: Int): Boolean {
            if (i <= 0 || i > actualEndIndex) return false
            val rightOk = i >= actualEndIndex || actualLabelTemps[i] <= actualLabelTemps[i + 1]
            return actualLabelTemps[i] <= actualLabelTemps[i - 1] && rightOk
        }
        fun isActualLocalMax(i: Int): Boolean {
            if (i <= 0 || i > actualEndIndex) return false
            val rightOk = i >= actualEndIndex || actualLabelTemps[i] >= actualLabelTemps[i + 1]
            return actualLabelTemps[i] >= actualLabelTemps[i - 1] && rightOk
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
        val actualDailyHighIndices = actualByDay.values.mapNotNull { d -> d.maxByOrNull { actualLabelTemps[it] } }.filter { isActualLocalMax(it) && dayHighReached(it) }.sorted()
        val actualDailyLowIndices = actualByDay.values.mapNotNull { d -> d.minByOrNull { actualLabelTemps[it] } }.filter { isActualLocalMin(it) }.sorted()

        Log.d(TAG, "ACTUAL_EXTREMA highIdx=$actualHighIndex highTemp=${if (actualHighIndex >= 0) actualLabelTemps[actualHighIndex] else "N/A"} " +
                "lowIdx=$actualLowIndex lowTemp=${if (actualLowIndex >= 0) actualLabelTemps[actualLowIndex] else "N/A"} " +
                "actualIndicesRange=${actualIndices.firstOrNull()}..${actualIndices.lastOrNull()}")
        Log.d(TAG, "ACTUAL_DAILY highIdxs=$actualDailyHighIndices highTemps=${actualDailyHighIndices.map { actualLabelTemps[it] }} " +
                "lowIdxs=$actualDailyLowIndices lowTemps=${actualDailyLowIndices.map { actualLabelTemps[it] }}")

        val forecastStartIndex = if (transitionX != null) effectiveActualEndIndex else 0
        val forecastIndices = (forecastStartIndex..hours.lastIndex).filter { it in labelTemps.indices }
        val forecastHighIndex = forecastIndices.maxByOrNull { labelTemps[it] } ?: -1
        val forecastLowIndex = forecastIndices.minByOrNull { labelTemps[it] } ?: -1

        val hasTransition = transitionX != null
        val pastForecastIndices = if (hasTransition) (0..actualEndIndex).filter { it in labelTemps.indices } else emptyList()
        val pastForecastHighIndex = if (hasTransition) pastForecastIndices.maxByOrNull { labelTemps[it] } ?: -1 else -1
        val pastForecastLowIndex = if (hasTransition) pastForecastIndices.minByOrNull { labelTemps[it] } ?: -1 else -1

        if (forecastHighIndex >= 0 && forecastLowIndex >= 0) {
            val forecastDates = forecastIndices.map { hours[it].dateTime.toLocalDate() }.distinct()
            Log.d(TAG, "FORECAST_EXTREMA highIdx=$forecastHighIndex highTemp=${labelTemps[forecastHighIndex]} " +
                "lowIdx=$forecastLowIndex lowTemp=${labelTemps[forecastLowIndex]} " +
                "forecastDates=$forecastDates forecastRange=$forecastStartIndex..${hours.lastIndex}")
        }

        val localExtrema = findLocalExtremaIndices(labelTemps)
        val significantLocalExtrema = localExtrema.filter { index ->
            val prom = bilateralExtremaProminence(index, labelTemps, localExtrema)
            if (prom < prominenceThreshold) {
                Log.d(TAG, "EXTREMUM_REJECTED idx=$index temp=${labelTemps[index]} prominence=$prom threshold=$prominenceThreshold")
                false
            } else {
                Log.d(TAG, "SIGNIFICANT_EXTREMUM idx=$index temp=${labelTemps[index]} prominence=$prom")
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
