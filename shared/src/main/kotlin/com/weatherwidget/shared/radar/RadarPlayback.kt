package com.weatherwidget.shared.radar

/**
 * Radar loop timing, both viewers: each frame shows [FRAME_MS], the newest frame is held
 * [LAST_FRAME_HOLD_MS] so "now" can be read before the loop restarts at the oldest. 13 frames
 * (2 h at 10 min) is an 8-s loop.
 *
 * Radar frames are pictures of the past, not forecast values, so there is no interpolation between
 * them (unlike the rain map's [com.weatherwidget.shared.rainmap.RainLoopSpeed]): a blend of two
 * frames would draw a cell that was never observed.
 */
object RadarPlayback {
    const val FRAME_MS = 500L
    const val LAST_FRAME_HOLD_MS = 2_000L

    fun loopMs(frameCount: Int): Long =
        if (frameCount <= 1) LAST_FRAME_HOLD_MS else (frameCount - 1) * FRAME_MS + LAST_FRAME_HOLD_MS

    /** The frame shown after [elapsedMs] of playback (wraps). */
    fun frameAt(elapsedMs: Long, frameCount: Int): Int {
        if (frameCount <= 1) return 0
        val t = Math.floorMod(elapsedMs, loopMs(frameCount))
        return (t / FRAME_MS).toInt().coerceAtMost(frameCount - 1)
    }

    /** Playback time at which [frame] starts — where play resumes after a scrub. */
    fun startOf(frame: Int): Long = frame.coerceAtLeast(0) * FRAME_MS

    /** "25 min ago" minutes, never negative (a clock a little behind the server's). */
    fun minutesAgo(frameTimeMs: Long, nowMs: Long): Long = ((nowMs - frameTimeMs) / 60_000L).coerceAtLeast(0)
}

/**
 * The colour key under the map. Only RainViewer publishes its palette (Universal Blue,
 * `rainviewer_api_colors_table.csv`, fetched 2026-10-21); the NWS mosaic is a pre-coloured
 * continuous gradient whose scale the server does not publish, so it gets no key rather than an
 * invented one.
 */
object RadarLegend {
    data class Stop(val dbz: Int, val argb: Int)

    private val UNIVERSAL_BLUE = listOf(
        Stop(15, 0xFF88DDEE.toInt()),
        Stop(20, 0xFF00A3E0.toInt()),
        Stop(25, 0xFF0077AA.toInt()),
        Stop(30, 0xFF005588.toInt()),
        Stop(35, 0xFFFFEE00.toInt()),
        Stop(40, 0xFFFFAA00.toInt()),
        Stop(45, 0xFFFF4400.toInt()),
        Stop(50, 0xFFC10000.toInt()),
        Stop(55, 0xFFFFAAFF.toInt()),
        Stop(60, 0xFFFF77FF.toInt()),
    )

    fun stops(feed: RadarFeed): List<Stop> = when (feed) {
        is RadarFeed.Nws -> emptyList()
        RadarFeed.RainViewer -> UNIVERSAL_BLUE
    }
}
