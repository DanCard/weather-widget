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

    /** The hour-bucket index of [tsMs]: `indexOf(13:47) == indexOf(14:00)`. */
    fun indexOf(tsMs: Long): Long = Math.round(tsMs / HOUR_MS.toDouble()).toLong()

    /** The epoch-ms start of the hour bucket [tsMs] rounds into. */
    fun startMsOf(tsMs: Long): Long = indexOf(tsMs) * HOUR_MS
}
