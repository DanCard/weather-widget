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
        val steps = sorted.zipWithNext { a, b -> b.timeMs - a.timeMs }
            .filter { it > 0L }
            .sorted()
        if (steps.isEmpty()) return listOf(sorted)
        val cadenceMs = steps[steps.size / 2]
        val maxBridgeMs = maxOf(cadenceMs * 2, MIN_STATION_SERIES_BRIDGE_MS)

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
     * Mixed station streams can contain dense five-minute timestamps alongside ordinary
     * 15-20-minute reports. This matches the observation blend's 30-minute anchor reach without
     * making the graph package depend on a specific provider or blend implementation.
     */
    private const val MIN_STATION_SERIES_BRIDGE_MS = 30 * 60_000L
}
