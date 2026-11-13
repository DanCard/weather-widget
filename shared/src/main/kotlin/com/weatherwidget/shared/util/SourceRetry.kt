package com.weatherwidget.shared.util

/**
 * A failed forecast fetch of a displayed source that waiting can fix (a timeout, no network, a 5xx) is
 * retried on its own: after 1, 2, 5 and 10 min ([DELAYS_MS], user 2026-11-12), then it waits for the next tap or
 * scheduled fetch. Never on battery with the screen off, neither armed nor run ([mayRun]): a provider
 * down all day must not wake a pocketed phone every hour (user, 2026-11-12). Before this, a
 * tap-stale source that timed out was not fetched again until its background cadence came due, hours
 * later (Fold 4 2026-11-12: `FORECAST_DUE_WAKE sleepMin=261 next=OPEN_METEO`), so a message could not say
 * when it would try again. A source nobody is viewing is retried only when the user asked for the fetch
 * (Settings → Refresh data: [onFailure]'s `requested`) and then for the rest of that chain (user,
 * 2026-11-13: Open-Meteo timed out in a refresh-all and was never tried again). The rule and its log line live here. The [Host] stores the pending retry
 * and arranges the wake (Android: a delayed targeted sync; desktop: the forecast loop).
 * plans/261112-refresh-failure-reason-and-retry.md
 */
object SourceRetry {
    const val LOG_TAG = "SOURCE_RETRY"

    /** The reason a retry fetch runs under (silent: no notice is owed for it). */
    const val REASON = "source_retry"

    /** The wait before each retry of a failure streak: 1, 2, 5, 10 min — 18 min in all. */
    val DELAYS_MS: List<Long> = listOf(1L, 2L, 5L, 10L).map { it * 60_000L }

    /** Retries per failure streak. */
    val MAX_RETRIES: Int = DELAYS_MS.size

    /** A chain whose last retry was due longer ago than this is over: a new failure starts a fresh one. */
    const val STREAK_GAP_MS = 60 * 60_000L

    /**
     * A retry the [Host] has armed: due at [atMs], [delayMs] after the failure that armed it, the
     * [attempt]th of its streak (1-based).
     */
    data class Pending(val atMs: Long, val delayMs: Long, val attempt: Int = 1)

    /** A retry may be armed or run on a charger or with the screen on; never on battery with it off. */
    fun mayRun(isCharging: Boolean, isScreenOn: Boolean): Boolean = isCharging || isScreenOn

    interface Host {
        val isCharging: Boolean
        val isScreenOn: Boolean

        /** Whether [sourceId] is on a screen now (a widget, the desktop's displayed source). */
        fun isDisplayed(sourceId: String): Boolean

        /** The armed retry of the current failure streak; null when none, or a fetch succeeded since. */
        fun pending(sourceId: String): Pending?

        /** Stores the retry and makes sure something wakes at [Pending.atMs] to fetch [sourceId]. */
        suspend fun arm(sourceId: String, retry: Pending)

        suspend fun log(tag: String, message: String, level: String)
    }

    /** Failures a later attempt can fix. Quotas, rate limits and 4xx have their own resets, or none. */
    fun isTransient(errorCode: String?): Boolean =
        errorCode != null && (errorCode in TRANSIENT_CODES || errorCode.startsWith("HTTP_5"))

    /** The [DELAYS_MS] entry for the attempt after [previous]. */
    private fun nextDelayMs(previous: Pending?): Long = DELAYS_MS[(previous?.attempt ?: 0).coerceIn(0, DELAYS_MS.lastIndex)]

    /** When the next attempt is, for a message: only a retry still ahead of [nowMs] counts. */
    fun retryAtMs(pending: Pending?, nowMs: Long): Long? = pending?.atMs?.takeIf { it > nowMs }

    /**
     * After a failed fetch of [sourceId] with [errorCode]: arms the next retry and returns its time, or
     * returns null (and says why in the log) when this failure does not get one. A source no screen shows
     * gets one only when [requested] (the fetch was the user's Settings → Refresh data) or it is already
     * in a retry chain, so the chain a requested failure started runs to its cap.
     */
    suspend fun onFailure(
        host: Host,
        sourceId: String,
        errorCode: String?,
        trigger: String,
        nowMs: Long,
        requested: Boolean = false,
    ): Long? {
        val previous = host.pending(sourceId)?.takeIf { nowMs - it.atMs < STREAK_GAP_MS }
        val skip = when {
            !isTransient(errorCode) -> "not_transient"
            !requested && previous == null && !host.isDisplayed(sourceId) -> "not_displayed"
            !mayRun(host.isCharging, host.isScreenOn) -> "battery_screen_off"
            (previous?.attempt ?: 0) >= MAX_RETRIES -> "cap"
            else -> null
        }
        if (skip != null) {
            host.log(LOG_TAG, "skip source=$sourceId code=$errorCode trigger=$trigger reason=$skip", "INFO")
            return null
        }
        val delayMs = nextDelayMs(previous)
        val retry = Pending(nowMs + delayMs, delayMs, attempt = (previous?.attempt ?: 0) + 1)
        host.arm(sourceId, retry)
        host.log(
            LOG_TAG,
            "scheduled source=$sourceId code=$errorCode trigger=$trigger attempt=${retry.attempt}/$MAX_RETRIES delayMin=${delayMs / 60_000L} at=${retry.atMs}",
            "INFO",
        )
        return retry.atMs
    }

    /**
     * When an armed retry of [sourceId] comes due: whether to fetch now. On battery with the screen off it
     * does not, and the chain ends there (a screen-on or a charger brings the normal re-plans back).
     */
    suspend fun shouldFire(isCharging: Boolean, isScreenOn: Boolean, sourceId: String, log: suspend (String, String, String) -> Unit): Boolean {
        val run = mayRun(isCharging, isScreenOn)
        log(LOG_TAG, "${if (run) "fire" else "fire_skip"} source=$sourceId charging=$isCharging screenOn=$isScreenOn" + if (run) "" else " reason=battery_screen_off", "INFO")
        return run
    }

    private val TRANSIENT_CODES = setOf("TIMEOUT", "DNS_ERROR", "CONN_REFUSED", "SOCKET_ERROR", "SSL_ERROR")
}
