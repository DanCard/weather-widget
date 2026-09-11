package com.weatherwidget.widget

import java.util.concurrent.ConcurrentHashMap

/**
 * One cache paint per widget at process start, whichever trigger gets there first.
 *
 * Two triggers paint every widget from cache when the process comes up after an install:
 * [PackageReplacedReceiver] (`MY_PACKAGE_REPLACED`, which Samsung's launcher sends *instead of* an
 * `APPWIDGET_UPDATE` — plans/260804-samsung-package-update-widget-rebind.md) and
 * [WeatherWidgetProvider.onUpdate] (which Pixel sends *as well*). On the Pixel both ran, back to
 * back, on a cold interpreter: three widgets × two full paints, ~35 s of the post-install storm
 * (`performance/260910-post-install-cold-start-storm.md`). The second paint shows nothing the
 * first did not.
 *
 * A claim is held for [CLAIM_WINDOW_MS] from when it was made. That is long enough to cover the
 * second startup trigger (which arrives seconds later, serialized behind the first's broadcast)
 * and short enough that an `onUpdate` for a resize or the periodic host update, minutes or hours
 * later, paints as it always has.
 */
internal object StartupPaintClaims {
    const val CLAIM_WINDOW_MS = 30_000L

    private data class Claim(val via: String, val atElapsedMs: Long)

    private val claims = ConcurrentHashMap<Int, Claim>()

    /**
     * Claims the startup paint of [appWidgetId] for [via]. Returns null when the claim is granted,
     * or the holder's `via` when another trigger painted this widget inside the window.
     */
    fun claim(appWidgetId: Int, via: String, nowElapsedMs: Long): String? {
        val fresh = Claim(via, nowElapsedMs)
        while (true) {
            val existing = claims.putIfAbsent(appWidgetId, fresh) ?: return null
            if (nowElapsedMs - existing.atElapsedMs < CLAIM_WINDOW_MS) return existing.via
            if (claims.replace(appWidgetId, existing, fresh)) return null
        }
    }

    /** Tests only: forget every claim so one test's startup does not shadow the next's. */
    fun clearForTests() = claims.clear()
}
