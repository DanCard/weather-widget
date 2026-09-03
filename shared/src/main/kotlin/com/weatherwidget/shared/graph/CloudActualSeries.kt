package com.weatherwidget.shared.graph

/** A source-scoped cloud-history value at its native provider timestamp. */
data class TimedCloudCover(
    val timeMs: Long,
    val cover: Int,
)

/** Shared normalization and gap handling for the desktop and Android solid cloud curves. */
object CloudActualSeries {

    /**
     * Produces a sorted, clamped series without manufacturing zeros or timestamps.
     * [endMs] is inclusive because a provider's current timestamp is a valid endpoint.
     */
    fun points(values: Map<Long, Int>, startMs: Long, endMs: Long): List<TimedCloudCover> =
        values.asSequence()
            .filter { (time, _) -> time in startMs..endMs }
            .map { (time, cover) -> TimedCloudCover(time, cover.coerceIn(0, 100)) }
            .sortedBy { it.timeMs }
            .toList()

    /**
     * Splits missing intervals instead of drawing a straight line across them. Cadence is inferred
     * from the MEDIAN positive step: a same-cadence series steps uniformly and the median is that
     * step, so 15-minute rows split beyond 30 minutes and hourly rows beyond two hours. For a
     * mixed station series, the bridge has a 30-minute floor matching the METAR anchor tolerance:
     * dense five-minute ASOS timestamps must not make ordinary 15-20-minute METAR intervals look
     * like missing data. The minimum step must NOT set the bridge — measured 2026-08-24 on the binless METAR blend
     * (plans/260824-subhourly-metar-cloud-blend.md): KNUQ reports at :15/:35/:55 and KSJC at :53,
     * so the smallest step is 2 minutes of station offset, bridging collapsed to 4 minutes, and
     * every measured point drew as an isolated DOT instead of a line. The median is unaffected by
     * those offset pairs and still pinpoints the true reporting cadence.
     */
    fun segments(points: List<TimedCloudCover>): List<List<TimedCloudCover>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(points)

        val sorted = points.sortedBy { it.timeMs }
        val maxBridgeMs = maxBridgeMs(sorted.map { it.timeMs }) ?: return listOf(sorted)

        val out = mutableListOf<MutableList<TimedCloudCover>>()
        var segment = mutableListOf(sorted.first())
        out += segment
        for (point in sorted.drop(1)) {
            if (point.timeMs - segment.last().timeMs > maxBridgeMs) {
                segment = mutableListOf()
                out += segment
            }
            segment += point
        }
        return out
    }

    /**
     * The bridge [segments] splits on, or null when the series has no positive step to measure.
     *
     * Exposed so that whatever *reacts* to a broken curve asks the identical question the curve was
     * drawn with. The two disagreed on 2026-09-03: the renderer split the Samsung's line over a
     * 40-minute hole while the observation backfill's own gap check — measured over every
     * observation row, temperature included — read a healthy `max_gap_min=23` and skipped the
     * re-fetch that would have filled it. See
     * plans/260903-refetch-when-the-cloud-actual-series-breaks.md.
     */
    fun maxBridgeMs(timesMs: List<Long>): Long? {
        val steps = timesMs.sorted()
            .zipWithNext { a, b -> b - a }
            .filter { it > 0L }
            .sorted()
        if (steps.isEmpty()) return null
        return maxOf(steps[steps.size / 2] * 2, MIN_STATION_SERIES_BRIDGE_MS)
    }

    /**
     * How far the series' widest hole sits from the bridge that would tolerate it.
     *
     * [largestGapMs] is reported even when it does not break, because "the curve is fine" and "the
     * curve is fine and the widest hole was 28 of an allowed 30 minutes" are different diagnostics,
     * and only the second one is any use in a log line after the fact.
     */
    data class Coverage(
        val largestGapMs: Long,
        val bridgeMs: Long,
    ) {
        /** True when [segments] would draw this series as more than one line. */
        val breaks: Boolean get() = largestGapMs > bridgeMs
    }

    /**
     * Coverage of a candidate-point series, or null below two distinct timestamps — one point (or
     * none) is not a broken line, it is a series with nothing to say about gaps.
     *
     * Takes raw timestamps rather than [TimedCloudCover] so a caller holding observation rows need
     * not build the values it is not going to use. Duplicates are collapsed first: several stations
     * report on one timestamp, and the blend emits one candidate point per DISTINCT time, so
     * counting them twice would drag the median cadence to zero and make every series look dense.
     */
    fun coverage(timesMs: List<Long>): Coverage? {
        val distinct = timesMs.distinct().sorted()
        if (distinct.size < 2) return null
        val bridge = maxBridgeMs(distinct) ?: return null
        val largest = distinct.zipWithNext { a, b -> b - a }.max()
        return Coverage(largestGapMs = largest, bridgeMs = bridge)
    }

    /**
     * Mixed station streams can contain dense five-minute timestamps alongside ordinary
     * 15-20-minute reports. This matches the observation blend's 30-minute anchor reach without
     * making the graph package depend on a specific provider or blend implementation.
     */
    private const val MIN_STATION_SERIES_BRIDGE_MS = 30 * 60_000L
}
