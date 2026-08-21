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
     * from the smallest positive step: 15-minute Meteo rows split beyond 30 minutes, while hourly
     * NWS/provider rows split beyond two hours.
     */
    fun segments(points: List<TimedCloudCover>): List<List<TimedCloudCover>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(points)

        val sorted = points.sortedBy { it.timeMs }
        val cadenceMs = sorted.zipWithNext { a, b -> b.timeMs - a.timeMs }
            .filter { it > 0L }
            .minOrNull()
            ?: return listOf(sorted)
        val maxBridgeMs = cadenceMs * 2

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
}
