package com.weatherwidget.desktop

import com.weatherwidget.shared.util.UserPresence
import java.time.ZoneId

/**
 * The daemon's away state ([UserPresence]): while nobody is at the machine the background observation
 * and forecast loops fetch nothing, and the return catches up at once
 * (performance/261019-desktop-away-state-no-fetching-while-nobody-is-there.md).
 *
 * [evaluate] runs every [CHECK_INTERVAL_MS] and on every screen signal; [markPresent] on anything the
 * user did (unblank, panel click, popup, resume). Each returns the transition to log and act on, or
 * null when nothing changed. A daemon start counts as presence.
 */
internal class DesktopPresence(
    private val screenState: () -> Boolean? = { ScreenStateDetector.screenState() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** An away ↔ present change: [line] is the `AWAY_STATE` message. */
    data class Transition(val away: Boolean, val line: String)

    private var lastPresentMs = clock()
    private var awaySinceMs: Long? = null
    private var skippedObservations = 0
    private var skippedForecasts = 0

    val isAway: Boolean
        @Synchronized get() = awaySinceMs != null

    /** Reads the screen: presence moves [lastPresentMs]; returns a transition when away starts or ends. */
    @Synchronized
    fun evaluate(): Transition? {
        val screen = screenState()
        val now = clock()
        if (UserPresence.isPresentNow(screen)) lastPresentMs = now
        val basis = UserPresence.away(screen, lastPresentMs, now, zone())
        return when {
            basis != null && awaySinceMs == null -> {
                awaySinceMs = now
                Transition(true, "away=true basis=${basis.name.lowercase()} lastPresentMinAgo=${(now - lastPresentMs) / 60_000L}")
            }
            basis == null && awaySinceMs != null -> back(now, "screen")
            else -> null
        }
    }

    /** The user did something ([trigger]): present now, ending any away. */
    @Synchronized
    fun markPresent(trigger: String): Transition? {
        val now = clock()
        lastPresentMs = now
        return if (awaySinceMs != null) back(now, trigger) else null
    }

    /** A background observation fetch the loop did not make while away. */
    @Synchronized
    fun countSkippedObservation() {
        skippedObservations++
    }

    /** Background forecast fetches (sources due) the loop did not make while away. */
    @Synchronized
    fun countSkippedForecasts(n: Int) {
        skippedForecasts += n
    }

    private fun back(now: Long, trigger: String): Transition {
        val since = awaySinceMs ?: now
        val line = "away=false trigger=$trigger awayMin=${(now - since) / 60_000L} " +
            "skippedObs=$skippedObservations skippedForecast=$skippedForecasts"
        awaySinceMs = null
        skippedObservations = 0
        skippedForecasts = 0
        return Transition(false, line)
    }

    companion object {
        const val LOG_TAG = "AWAY_STATE"

        /** How often the screen is read for presence; also bounds how late a return with no signal is seen. */
        const val CHECK_INTERVAL_MS = 60_000L
    }
}
