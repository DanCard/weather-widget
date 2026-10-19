package com.weatherwidget.shared.util

import java.time.Instant
import java.time.ZoneId

/**
 * Whether anyone is at the machine, for the desktop's away state: no background fetching while
 * nobody is there (user, 2026-10-19;
 * performance/261019-desktop-away-state-no-fetching-while-nobody-is-there.md).
 *
 * **Present** = screen on and session unlocked, or the user just used the panel / popup. **Away** =
 * not present for [AWAY_AFTER_MS] — a lunch break's blank screen stays under it. When the screen
 * state cannot be read at all (no `xset`, no `loginctl`), the machine is treated as present by day,
 * fail-open as before, and as away in [NIGHT_START_HOUR]–[NIGHT_END_HOUR] local once nobody has
 * used it for [AWAY_AFTER_MS] ([Basis.NIGHT_FALLBACK]). Where the state can be read the hour plays no
 * part: a 2 am user has the screen on, and a deliberate overnight weather display keeps fetching.
 */
object UserPresence {
    const val AWAY_AFTER_MS = 60 * 60_000L
    const val NIGHT_START_HOUR = 0
    const val NIGHT_END_HOUR = 5

    /** What made the machine away. */
    enum class Basis { SCREEN, NIGHT_FALLBACK }

    /**
     * Away, and why, or null when present. [screenOn] is true for on and unlocked, false for off or
     * locked, null when unreadable. [lastPresentMs] is the newest presence seen ([isPresentNow] or a
     * panel / popup interaction).
     */
    fun away(screenOn: Boolean?, lastPresentMs: Long, nowMs: Long, zone: ZoneId): Basis? {
        if (screenOn == true) return null
        if (nowMs - lastPresentMs < AWAY_AFTER_MS) return null
        if (screenOn == false) return Basis.SCREEN
        val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
        return if (hour in NIGHT_START_HOUR until NIGHT_END_HOUR) Basis.NIGHT_FALLBACK else null
    }

    /** Whether this screen reading is itself presence (moves `lastPresentMs`). Unreadable is not. */
    fun isPresentNow(screenOn: Boolean?): Boolean = screenOn == true
}
