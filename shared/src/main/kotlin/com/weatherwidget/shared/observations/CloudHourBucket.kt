package com.weatherwidget.shared.observations

/**
 * The one round-to-nearest-hour bucketing rule for observation timestamps, shared by the METAR
 * cloud blend, its diagnostics, and the Android self-heal gap check.
 *
 * Round-to-nearest (not floor) is load-bearing: a 13:47 METAR is an instantaneous reading 13
 * minutes from 14:00 and 47 from 13:00, and the graph plots instantaneous values at hour marks.
 * Flooring instead dropped KPAO — which reports at :47 — almost entirely from the blend.
 */
object CloudHourBucket {
    private const val HOUR_MS = 3_600_000L

    /**
     * How far from an hour mark a report can sit and still round into it.
     *
     * The rounding rule reaches BACKWARDS as well as forwards, so the row read that feeds the blend
     * must reach back too — see [readStartMs].
     */
    const val TOLERANCE_MS = HOUR_MS / 2

    /** The hour-bucket index of [tsMs]: `indexOf(13:47) == indexOf(14:00)`. */
    fun indexOf(tsMs: Long): Long = Math.round(tsMs / HOUR_MS.toDouble()).toLong()

    /** The epoch-ms start of the hour bucket [tsMs] rounds into. */
    fun startMsOf(tsMs: Long): Long = indexOf(tsMs) * HOUR_MS

    /**
     * Start of the raw-timestamp range a caller must read to fill the bucket at [windowStartMs].
     *
     * Reading the visible window verbatim silently truncates its first hour: reports in the
     * half-hour BEFORE the first hour mark round into it but fail a `timestamp >= windowStartMs`
     * filter. Observed 2026-08-21 on the Samsung fold — the 1a-5a cloud graph's actual curve began
     * at 2a because KSJC's 00:30 METAR, which buckets to 01:00, was never fetched. The leading hour
     * could only be served by a report at or after the mark, i.e. half the tolerance this object
     * otherwise promises.
     *
     * Padding the READ only; the emitted hours stay bounded by the unpadded window, which is what
     * makes this safe to apply at every call site.
     */
    fun readStartMs(windowStartMs: Long): Long = windowStartMs - TOLERANCE_MS

    /** End of the raw-timestamp range to read for a window ending at [windowEndMs]. See [readStartMs]. */
    fun readEndMs(windowEndMs: Long): Long = windowEndMs + TOLERANCE_MS
}
