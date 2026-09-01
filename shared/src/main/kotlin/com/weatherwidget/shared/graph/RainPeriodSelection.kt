package com.weatherwidget.shared.graph

import java.time.LocalDateTime

/**
 * Chooses which stretches of the hourly precip window get a rain-amount label, and what each one
 * totals — for the forecast series and the measured series alike.
 *
 * **Why this is shared.** Android and desktop each carried their own copy of `dayNightRuns`,
 * `selectDayNightSegments`, `toRainPeriod` and `perHourRainPeriods`. Desktop's copy summed the
 * *forecast* field for both series and had no now-gate, so its orange "Actual: " label showed the
 * forecast number, at anchors that could sit entirely in the future. Observed 2026-09-01 on Silurian
 * — a source with no observation rows at all, which collapses the two calls into literally the same
 * one. Android was correct and is the behaviour preserved here. See
 * plans/260901-share-rain-period-selection.md.
 *
 * **Why [selectPeriods] returns both series together.** The defect was a caller passing the forecast
 * accessor twice. An API that takes an accessor per series invites exactly that, so the field choice
 * lives in here where it can be tested once, and callers get a [RainPeriods] pair they cannot
 * mis-wire.
 */
object RainPeriodSelection {

    /**
     * NARROW zoom shows per-hour labels for the first few columns; the rightmost column sits at the
     * clipped window edge, so cap at the first 4.
     */
    const val PER_HOUR_MAX_COLUMNS = 4

    /**
     * One hour of the window, carrying both precip series.
     *
     * [actualPrecipAmountMm] must be null for any hour that has not elapsed. Callers own that gate
     * because only they know the window's "now"; passing a forecast value here is the bug this file
     * exists to prevent.
     */
    data class RainHour(
        val dateTime: LocalDateTime,
        val precipAmountMm: Float? = null,
        val actualPrecipAmountMm: Float? = null,
        /** Hour label ("12a", "1p") used for window-total period text. Blank where unused. */
        val label: String = "",
    )

    data class RainPeriod(
        val startIndex: Int,
        val endIndex: Int,
        val totalAmountMm: Float,
        val startLabel: String = "",
        val endLabel: String = "",
        /** When set, label placement is biased to sit near this x (px) instead of floating freely. */
        val anchorX: Float? = null,
    )

    /** The two label series for one window. Never derived independently — see [selectPeriods]. */
    data class RainPeriods(
        val forecast: List<RainPeriod>,
        val actual: List<RainPeriod>,
    )

    /** How rain-amount labels are aggregated across the visible window. */
    enum class Mode {
        /** A single total spanning the fixed window (or the whole visible span when unbounded). */
        WINDOW_TOTAL,

        /** WIDE zoom: wettest day (8a-8p) + wettest night (8p-8a) segment, each anchored to its region. */
        DAY_NIGHT,

        /** NARROW zoom: per-hour forecast/actual for the first few hours, only where rain exists. */
        PER_HOUR,
    }

    /** A contiguous run of hours sharing the same clock-based day/night phase. */
    data class DayNightSegment(
        val startIndex: Int,
        val endIndex: Int,
        val isDay: Boolean,
    ) {
        fun centerX(hourWidth: Float): Float = hourWidth * (startIndex + endIndex) / 2f

        /** Sums [amountFor] over the segment; null when the segment has no rain of that kind. */
        fun toRainPeriod(
            hours: List<RainHour>,
            hourWidth: Float,
            amountFor: (RainHour) -> Float?,
        ): RainPeriod? {
            val total = (startIndex..endIndex)
                .sumOf { (amountFor(hours[it]) ?: 0f).toDouble() }
                .toFloat()
            if (total <= 0f) return null
            return RainPeriod(
                startIndex = startIndex,
                endIndex = endIndex,
                totalAmountMm = total,
                startLabel = hours[startIndex].label,
                endLabel = hours[endIndex].label,
                anchorX = centerX(hourWidth),
            )
        }
    }

    /**
     * The single entry point both platforms call. Picks the periods for [mode] and totals each one
     * over the forecast field and the actual field respectively.
     *
     * @param windowHours fixed window width for [Mode.WINDOW_TOTAL]; <= 0 means the whole span.
     */
    fun selectPeriods(
        hours: List<RainHour>,
        mode: Mode,
        hourWidth: Float,
        windowHours: Int = 0,
    ): RainPeriods = when (mode) {
        Mode.DAY_NIGHT -> {
            // Both series use the SAME segments on purpose: the day/night anchors are regions of
            // the window, so forecast and actual totals for one region must land in one place.
            val segments = selectDayNightSegments(hours)
            RainPeriods(
                forecast = segments.mapNotNull { it.toRainPeriod(hours, hourWidth) { h -> h.precipAmountMm } },
                actual = segments.mapNotNull { it.toRainPeriod(hours, hourWidth) { h -> h.actualPrecipAmountMm } },
            )
        }
        Mode.PER_HOUR -> RainPeriods(
            forecast = perHourRainPeriods(hours, hourWidth) { it.precipAmountMm },
            actual = perHourRainPeriods(hours, hourWidth) { it.actualPrecipAmountMm },
        )
        Mode.WINDOW_TOTAL -> {
            if (windowHours > 0) {
                RainPeriods(
                    forecast = findFixedWindowRainPeriods(hours, windowHours) { it.precipAmountMm },
                    actual = findFixedWindowRainPeriods(hours, windowHours) { it.actualPrecipAmountMm },
                )
            } else {
                RainPeriods(
                    forecast = findVisibleWindowRainPeriods(hours) { it.precipAmountMm },
                    actual = findVisibleWindowRainPeriods(hours) { it.actualPrecipAmountMm },
                )
            }
        }
    }

    /** Splits [hours] into contiguous runs grouped by clock-based day (8a-8p) vs night (8p-8a). */
    fun dayNightRuns(hours: List<RainHour>): List<DayNightSegment> {
        if (hours.isEmpty()) return emptyList()
        val runs = mutableListOf<DayNightSegment>()
        var start = 0
        var currentIsDay = DayNightHours.isDayHour(hours[0].dateTime)
        for (i in 1..hours.lastIndex) {
            val isDay = DayNightHours.isDayHour(hours[i].dateTime)
            if (isDay != currentIsDay) {
                runs.add(DayNightSegment(start, i - 1, currentIsDay))
                start = i
                currentIsDay = isDay
            }
        }
        runs.add(DayNightSegment(start, hours.lastIndex, currentIsDay))
        return runs
    }

    /**
     * Picks at most the wettest day run and the wettest night run (by combined forecast + actual
     * rainfall), so a busy 24h window reads as two anchored regions instead of one merged total.
     *
     * Ranking on the combined total is deliberate: a region that has already rained should stay
     * anchored even once its forecast has been superseded, and vice versa. It is only the ranking —
     * the drawn totals come from one field each, in [selectPeriods].
     */
    fun selectDayNightSegments(hours: List<RainHour>): List<DayNightSegment> {
        fun combinedTotal(seg: DayNightSegment): Float =
            (seg.startIndex..seg.endIndex).sumOf { idx ->
                ((hours[idx].precipAmountMm ?: 0f) + (hours[idx].actualPrecipAmountMm ?: 0f)).toDouble()
            }.toFloat()

        val runs = dayNightRuns(hours)
        val wettestDay = runs.filter { it.isDay }
            .maxByOrNull { combinedTotal(it) }
            ?.takeIf { combinedTotal(it) > 0f }
        val wettestNight = runs.filterNot { it.isDay }
            .maxByOrNull { combinedTotal(it) }
            ?.takeIf { combinedTotal(it) > 0f }
        return listOfNotNull(wettestDay, wettestNight).sortedBy { it.startIndex }
    }

    /** One RainPeriod per hour for the first [PER_HOUR_MAX_COLUMNS] columns where [amountFor] > 0. */
    fun perHourRainPeriods(
        hours: List<RainHour>,
        hourWidth: Float,
        amountFor: (RainHour) -> Float?,
    ): List<RainPeriod> {
        val limit = minOf(PER_HOUR_MAX_COLUMNS, hours.size)
        val periods = mutableListOf<RainPeriod>()
        for (i in 0 until limit) {
            val amount = amountFor(hours[i]) ?: continue
            if (amount <= 0f) continue
            periods.add(
                RainPeriod(
                    startIndex = i,
                    endIndex = i,
                    totalAmountMm = amount,
                    startLabel = hours[i].label,
                    endLabel = hours[i].label,
                    anchorX = hourWidth * i,
                ),
            )
        }
        return periods
    }

    /** A single period spanning the whole visible window, when it holds any rain of that kind. */
    fun findVisibleWindowRainPeriods(
        hours: List<RainHour>,
        amountFor: (RainHour) -> Float?,
    ): List<RainPeriod> {
        if (hours.isEmpty()) return emptyList()
        val totalMm = hours.sumOf { (amountFor(it) ?: 0f).toDouble() }.toFloat()
        if (totalMm <= 0f) return emptyList()
        return listOf(
            RainPeriod(
                startIndex = 0,
                endIndex = hours.lastIndex,
                totalAmountMm = totalMm,
                startLabel = hours.first().label,
                endLabel = hours.last().label,
            ),
        )
    }

    /** The wettest [windowHours]-wide run, by [amountFor]. */
    fun findFixedWindowRainPeriods(
        hours: List<RainHour>,
        windowHours: Int,
        amountFor: (RainHour) -> Float?,
    ): List<RainPeriod> {
        if (windowHours <= 0 || hours.size < windowHours) return emptyList()
        var bestPeriod: RainPeriod? = null
        var bestTotal = 0f
        var i = 0
        while (i <= hours.size - windowHours) {
            val window = hours.subList(i, i + windowHours)
            val totalMm = window.sumOf { (amountFor(it) ?: 0f).toDouble() }.toFloat()
            if (totalMm > bestTotal) {
                bestTotal = totalMm
                bestPeriod = RainPeriod(
                    startIndex = i,
                    endIndex = i + windowHours - 1,
                    totalAmountMm = totalMm,
                    startLabel = hours[i].label,
                    endLabel = hours[i + windowHours - 1].label,
                )
            }
            i++
        }
        return if (bestPeriod != null) listOf(bestPeriod) else emptyList()
    }

    /** X (px) of each 8a/8p day-night transition inside the window, at the first hour of the new phase. */
    fun computeDayNightBoundaryXs(hours: List<RainHour>, hourWidth: Float): List<Float> {
        if (hours.size < 2) return emptyList()
        val xs = mutableListOf<Float>()
        for (i in 1..hours.lastIndex) {
            if (DayNightHours.isDayHour(hours[i].dateTime) != DayNightHours.isDayHour(hours[i - 1].dateTime)) {
                xs.add(hourWidth * i)
            }
        }
        return xs
    }
}
