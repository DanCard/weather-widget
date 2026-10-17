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
 * (`performance/260910-post-install-cold-start-storm.md`). The partner's paint shows nothing the
 * first did not.
 *
 * A claim is held for [CLAIM_WINDOW_MS] from when it was made, and suppresses only the **other**
 * trigger, **once**. Within the window:
 * - the other trigger's first paint is refused ([Decision.Skip]) and the claim is spent;
 * - the same trigger again is a new host request, not the partner's duplicate, and paints
 *   ([Decision.Repeat]). A Samsung fold/unfold re-adds the widgets with no views and sends
 *   `onUpdate` again; refusing that left the Fold's inner widget on the layout defaults
 *   (plans/261017-startup-paint-claim-skips-host-rebind.md);
 * - a spent claim refuses nothing more.
 * Past the window an `onUpdate` for a resize or the periodic host update paints as it always has.
 */
internal object StartupPaintClaims {
    const val CLAIM_WINDOW_MS = 30_000L

    sealed interface Decision {
        /** Nothing held this widget (or the claim had expired or was spent): paint. */
        data object Granted : Decision

        /** The same trigger asked again inside the window: paint. */
        data class Repeat(val holderAgeMs: Long) : Decision

        /** The other trigger already painted this widget inside the window: skip. */
        data class Skip(val holder: String) : Decision
    }

    private data class Claim(val via: String, val atElapsedMs: Long, val spent: Boolean = false)

    private val claims = ConcurrentHashMap<Int, Claim>()

    /** Claims the startup paint of [appWidgetId] for [via]; see the class doc for the rule. */
    fun claim(appWidgetId: Int, via: String, nowElapsedMs: Long): Decision {
        val fresh = Claim(via, nowElapsedMs)
        while (true) {
            val existing = claims.putIfAbsent(appWidgetId, fresh) ?: return Decision.Granted
            val ageMs = nowElapsedMs - existing.atElapsedMs
            val decision =
                when {
                    ageMs >= CLAIM_WINDOW_MS || existing.spent -> Decision.Granted
                    existing.via == via -> Decision.Repeat(ageMs)
                    else -> Decision.Skip(existing.via)
                }
            val next = if (decision is Decision.Skip) existing.copy(spent = true) else fresh
            if (claims.replace(appWidgetId, existing, next)) return decision
        }
    }

    /** Tests only: forget every claim so one test's startup does not shadow the next's. */
    fun clearForTests() = claims.clear()
}
