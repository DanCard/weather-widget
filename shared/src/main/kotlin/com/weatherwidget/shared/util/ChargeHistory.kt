package com.weatherwidget.shared.util

/**
 * What the device's charger did: every charge session and every clock alarm that fired, kept for
 * [RETENTION_DAYS] — the evidence [UnplugPredictor] learns from (plans/261028-learned-unplug-prediction.md).
 *
 * Neither platform can be sure to hear every unplug (Android delivers no background
 * `ACTION_POWER_DISCONNECTED`; a desktop UPower monitor can die), so a session ends at the exact power
 * event when there was one, else at the last sighting while charging — at most [MAX_ESTIMATE_GAP_MS]
 * early, or the session is dropped as unbounded. Sessions shorter than [MIN_SESSION_MINUTES] (moving the
 * phone, a cable wiggle) are not kept. Daytime charges and top-ups are: they are the irregular habits.
 *
 * Pure state + transitions; [ChargeHistoryStore] persists [State] on each platform.
 */
object ChargeHistory {
    const val RETENTION_DAYS = 56
    const val MAX_SESSIONS = 400
    const val MAX_ALARMS = 200
    const val MIN_SESSION_MINUTES = 15
    const val MAX_ESTIMATE_GAP_MS = 45 * 60_000L

    private const val DAY_MS = 86_400_000L

    /** One charge: plugged at, unplugged at, and whether the unplug time is the power event itself. */
    data class Session(val pluggedAtMs: Long, val unpluggedAtMs: Long, val exact: Boolean)

    data class State(
        /** The open session's start; null while on battery. */
        val chargingSinceMs: Long? = null,
        val lastSeenChargingMs: Long? = null,
        val sessions: List<Session> = emptyList(),
        /** Alarms that fired. */
        val alarms: List<Long> = emptyList(),
        /** The next alarm last seen; recorded in [alarms] once it has passed. */
        val pendingAlarmMs: Long? = null,
        /** The predicted unplug a pre-unplug check is armed for, and what predicted it. */
        val armedWakeAtMs: Long? = null,
        val armedSource: String? = null,
        /** The armed prediction still awaiting its hit/miss after it passes (`PRE_UNPLUG_OUTCOME`). */
        val trackedWakeAtMs: Long? = null,
        val trackedSource: String? = null,
        /** Local date of the last `UNPLUG_PATTERNS` line. */
        val patternLogDate: String? = null,
    ) {
        /** Whether the device is known to have been charging at [ms]. */
        fun chargingAt(ms: Long): Boolean =
            sessions.any { ms >= it.pluggedAtMs && ms < it.unpluggedAtMs } ||
                (chargingSinceMs != null && ms >= chargingSinceMs && ms <= (lastSeenChargingMs ?: chargingSinceMs))

        /** Unplug instants in [fromMs, toMs]. */
        fun unplugsBetween(fromMs: Long, toMs: Long): List<Long> =
            sessions.map { it.unpluggedAtMs }.filter { it in fromMs..toMs }

        fun encode(): String = listOf(
            VERSION,
            chargingSinceMs.enc(),
            lastSeenChargingMs.enc(),
            pendingAlarmMs.enc(),
            armedWakeAtMs.enc(),
            armedSource ?: NONE,
            trackedWakeAtMs.enc(),
            trackedSource ?: NONE,
            patternLogDate ?: NONE,
            sessions.joinToString(";") { "${it.pluggedAtMs},${it.unpluggedAtMs},${if (it.exact) 1 else 0}" },
            alarms.joinToString(";"),
        ).joinToString("|")

        companion object {
            private const val VERSION = "v1"
            private const val NONE = "-"

            private fun Long?.enc(): String = this?.toString() ?: NONE
            private fun String.decLong(): Long? = if (this == NONE) null else toLongOrNull()
            private fun String.decStr(): String? = if (this == NONE || isEmpty()) null else this

            /** Null for an unreadable value; the caller starts empty. */
            fun decode(raw: String?): State? {
                val p = raw?.split('|') ?: return null
                if (p.size != 11 || p[0] != VERSION) return null
                val sessions = p[9].split(';').filter { it.isNotEmpty() }.mapNotNull { entry ->
                    val f = entry.split(',')
                    if (f.size != 3) return@mapNotNull null
                    Session(f[0].toLongOrNull() ?: return@mapNotNull null, f[1].toLongOrNull() ?: return@mapNotNull null, f[2] == "1")
                }
                return State(
                    chargingSinceMs = p[1].decLong(),
                    lastSeenChargingMs = p[2].decLong(),
                    pendingAlarmMs = p[3].decLong(),
                    armedWakeAtMs = p[4].decLong(),
                    armedSource = p[5].decStr(),
                    trackedWakeAtMs = p[6].decLong(),
                    trackedSource = p[7].decStr(),
                    patternLogDate = p[8].decStr(),
                    sessions = sessions,
                    alarms = p[10].split(';').mapNotNull { it.toLongOrNull() },
                )
            }
        }
    }

    /** The result of one power sighting: the new state and an `UNPLUG_RECORDED` line when a charge ended. */
    data class Observed(val state: State, val logLine: String?, val unplugAtMs: Long?)

    /** One power sighting. [exact] when it is the platform's power event itself. */
    fun observe(state: State, isCharging: Boolean, nowMs: Long, exact: Boolean): Observed {
        val since = state.chargingSinceMs
        if (isCharging) {
            return Observed(state.copy(chargingSinceMs = since ?: nowMs, lastSeenChargingMs = nowMs), null, null)
        }
        if (since == null) return Observed(state, null, null)
        val lastSeen = state.lastSeenChargingMs ?: since
        val closed = state.copy(chargingSinceMs = null, lastSeenChargingMs = null)
        val unplugAt = if (exact) nowMs else lastSeen
        val sessionMin = (unplugAt - since) / 60_000L
        val estimate = if (exact) "exact" else "last_seen"
        if (!exact && nowMs - lastSeen > MAX_ESTIMATE_GAP_MS) {
            return Observed(closed, "action=skipped reason=unbounded gapMin=${(nowMs - lastSeen) / 60_000L} sessionMin=$sessionMin", null)
        }
        if (sessionMin < MIN_SESSION_MINUTES) {
            return Observed(closed, "action=skipped reason=brief sessionMin=$sessionMin estimate=$estimate", null)
        }
        val sessions = trimmed(closed.sessions + Session(since, unplugAt, exact), nowMs)
        return Observed(
            closed.copy(sessions = sessions),
            "action=recorded at=$unplugAt sessionMin=$sessionMin estimate=$estimate n=${sessions.size}",
            unplugAt,
        )
    }

    /** Records the last-seen alarm once it has passed, then remembers [nextAlarmMs]. */
    fun noteAlarm(state: State, nextAlarmMs: Long?, nowMs: Long): State {
        val pending = state.pendingAlarmMs
        val alarms = if (pending != null && pending <= nowMs && pending !in state.alarms) {
            (state.alarms + pending).filter { it >= nowMs - RETENTION_DAYS * DAY_MS }.takeLast(MAX_ALARMS)
        } else {
            state.alarms
        }
        val nextPending = nextAlarmMs?.takeIf { it > nowMs } ?: pending?.takeIf { it > nowMs }
        return state.copy(alarms = alarms, pendingAlarmMs = nextPending)
    }

    private fun trimmed(sessions: List<Session>, nowMs: Long): List<Session> =
        sessions.filter { it.unpluggedAtMs >= nowMs - RETENTION_DAYS * DAY_MS }.takeLast(MAX_SESSIONS)
}

/** Where a platform keeps [ChargeHistory.State]: Android prefs, a desktop file. */
interface ChargeHistoryStore {
    fun load(): ChargeHistory.State
    fun save(state: ChargeHistory.State)
}
