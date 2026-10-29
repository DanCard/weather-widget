package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.ForecastHorizonCadence
import java.time.Instant
import java.time.ZoneId

/**
 * The whole pre-unplug rule for both platforms (plans/261028-learned-unplug-prediction.md): records
 * charge sessions and fired alarms ([ChargeHistory]), predicts the next unplug ([UnplugPredictor]),
 * arms one platform check inside [ForecastHorizonCadence.PRE_WAKE_LEAD_MS] before it, and logs what it
 * predicted against what happened. Whether that check then fetches stays the cadence's call — the
 * sync's fetch context carries [nextWake] as its `wakeAtMs`.
 *
 * The [Host] supplies storage, the clock, the next clock alarm (none on desktop) and the check timer
 * (Android WorkManager, the desktop forecast loop's wake). One runner per store: [lock] serialises the
 * load-modify-save of every caller in the process.
 */
class PreUnplugFetchRunner(private val host: Host) {
    interface Host {
        val store: ChargeHistoryStore
        fun nowMs(): Long
        fun zone(): ZoneId = ZoneId.systemDefault()

        /** The user's next clock alarm; null when none or the platform has none. */
        fun nextAlarmMs(): Long?

        /** Arms the platform's check [delayMs] from now, replacing an earlier one. */
        fun scheduleCheck(delayMs: Long)

        /** Drops a pending check — never a running one. */
        fun cancelCheck()

        suspend fun log(tag: String, message: String, level: String)
    }

    private val lock = Any()

    /** The next predicted unplug, for the fetch context's `wakeAtMs`; null when none is predicted. */
    fun nextWake(): UnplugPredictor.Prediction? =
        UnplugPredictor.predictNext(host.store.load(), host.nextAlarmMs(), host.nowMs(), host.zone())

    /** One power sighting; [exact] when it is the platform's power event itself. */
    suspend fun observePower(isCharging: Boolean, exact: Boolean = false) {
        val lines = synchronized(lock) {
            val loaded = host.store.load()
            val now = host.nowMs()
            val observed = ChargeHistory.observe(loaded, isCharging, now, exact)
            var state = observed.state
            val out = mutableListOf<Pair<String, String>>()
            observed.logLine?.let { out += UNPLUG_TAG to it }
            if (observed.unplugAtMs != null) {
                val (resolved, line) = resolveOutcome(state, now, Settle.ON_UNPLUG)
                state = resolved
                line?.let { out += OUTCOME_TAG to it }
            }
            if (state != loaded) host.store.save(state)
            out
        }
        lines.forEach { (tag, line) -> host.log(tag, line, "INFO") }
    }

    /**
     * Arms, re-arms or cancels the check for the next predicted unplug; first records a passed alarm and
     * settles a passed prediction. Logs `PRE_UNPLUG_FETCH` when the armed state changed.
     */
    suspend fun arm(trigger: String) {
        val lines = synchronized(lock) {
            val loaded = host.store.load()
            val now = host.nowMs()
            val alarm = host.nextAlarmMs()
            val out = mutableListOf<Pair<String, String>>()
            val (resolved, outcome) = resolveOutcome(ChargeHistory.noteAlarm(loaded, alarm, now), now, Settle.WINDOW_CLOSED)
            outcome?.let { out += OUTCOME_TAG to it }
            var state = resolved
            val prediction = UnplugPredictor.predictNext(state, alarm, now, host.zone())
            val armed = state.armedWakeAtMs
            val checkAt = ForecastHorizonCadence.preWakeCheckAtMs(prediction?.atMs, now)
            if (prediction == null || checkAt == null) {
                if (armed != null) {
                    state = state.copy(armedWakeAtMs = null, armedSource = null)
                    if (armed > now) {
                        // Withdrawn before it came: nothing to judge. A passed one stays tracked until its window closes.
                        host.cancelCheck()
                        state = state.copy(trackedWakeAtMs = null, trackedSource = null)
                    }
                    out += FETCH_TAG to "action=cancelled reason=${if (armed <= now) "passed" else "no_prediction"} armedWake=${clock(armed)} trigger=$trigger"
                }
            } else if (armed != prediction.atMs) {
                if (state.trackedWakeAtMs != null && state.trackedWakeAtMs != prediction.atMs) {
                    // A new prediction while the last one's window is still open: judge it on what is known now.
                    val (settled, line) = resolveOutcome(state, now, Settle.SUPERSEDED)
                    state = settled
                    line?.let { out += OUTCOME_TAG to it }
                }
                host.scheduleCheck(checkAt - now)
                state = state.copy(
                    armedWakeAtMs = prediction.atMs,
                    armedSource = prediction.source,
                    trackedWakeAtMs = prediction.atMs,
                    trackedSource = prediction.source,
                )
                out += FETCH_TAG to "action=armed at=${clock(checkAt)} predictedUnplug=${clock(prediction.atMs)} " +
                    "source=${prediction.source} p=${"%.2f".format(prediction.rate)} delayMin=${(checkAt - now) / 60_000L} trigger=$trigger"
            }
            if (state != loaded) host.store.save(state)
            out
        }
        lines.forEach { (tag, line) -> host.log(tag, line, "INFO") }
    }

    /** The learned patterns, once per local day (`UNPLUG_PATTERNS`). */
    suspend fun dailyPatternLog() {
        val line = synchronized(lock) {
            val loaded = host.store.load()
            val now = host.nowMs()
            val today = Instant.ofEpochMilli(now).atZone(host.zone()).toLocalDate().toString()
            if (loaded.patternLogDate == today) return
            host.store.save(loaded.copy(patternLogDate = today))
            UnplugPredictor.logLine(loaded, host.nextAlarmMs(), now, host.zone())
        }
        host.log(PATTERNS_TAG, line, "INFO")
    }

    private enum class Settle { ON_UNPLUG, WINDOW_CLOSED, SUPERSEDED }

    /**
     * Settles the tracked prediction: on an unplug inside its ±[UnplugPredictor.ALARM_FOLLOW_MINUTES]
     * window, once that window has closed, or when a new prediction supersedes it. `PRE_UNPLUG_OUTCOME`
     * hit/miss/unknown.
     */
    private fun resolveOutcome(state: ChargeHistory.State, nowMs: Long, settle: Settle): Pair<ChargeHistory.State, String?> {
        val tracked = state.trackedWakeAtMs ?: return state to null
        val follow = UnplugPredictor.ALARM_FOLLOW_MINUTES * 60_000L
        val unplug = state.unplugsBetween(tracked - follow, minOf(nowMs, tracked + follow)).minOrNull()
        when (settle) {
            Settle.ON_UNPLUG -> if (unplug == null) return state to null
            Settle.WINDOW_CLOSED -> if (nowMs < tracked + follow) return state to null
            Settle.SUPERSEDED -> Unit
        }
        val outcome = when {
            unplug != null -> "hit unplugged=${clock(unplug)} deltaMin=${(unplug - tracked) / 60_000L}"
            nowMs >= tracked && state.chargingAt(tracked) -> "miss"
            else -> "unknown"
        }
        val line = "outcome=$outcome source=${state.trackedSource} predicted=${clock(tracked)}"
        return state.copy(trackedWakeAtMs = null, trackedSource = null) to line
    }

    private fun clock(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(host.zone()).let { "%s %02d:%02d".format(it.toLocalDate(), it.hour, it.minute) }

    companion object {
        const val UNPLUG_TAG = "UNPLUG_RECORDED"
        const val FETCH_TAG = "PRE_UNPLUG_FETCH"
        const val OUTCOME_TAG = "PRE_UNPLUG_OUTCOME"
        const val PATTERNS_TAG = "UNPLUG_PATTERNS"
    }
}
