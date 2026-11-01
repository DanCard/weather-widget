package com.weatherwidget.shared.history

import kotlin.coroutines.cancellation.CancellationException

/**
 * Every fetch's hourly forecast is saved to `hourly_forecast_history` under its own fetch time — one rule
 * for both platforms and every source (user, 2026-11-01;
 * `plans/261101-every-fetch-saves-an-hourly-snapshot.md`).
 *
 * The rule skipped fetches from 2026-10-24: other sources saved only every 11 / 22 h, and sources not
 * viewed in 8 days never. But [com.weatherwidget.data.model.HourlyForecastStitcher] freezes an hour at the
 * last forecast fetched at or before it, and a fetch during that hour replaces its live row, so a skipped
 * forecast could disappear before it settled: Tomorrow.io's 97 % for 03:00 on 2026-11-01 fell to the
 * previous snapshot's 35 %. Unread snapshots are `HistorySnapshotRetention`'s to prune.
 *
 * A call within [SAME_FETCH_MS] of the source's last snapshot is the same fetch still being written
 * (the live rows, then the elapsed-hour backfill): it gets that snapshot's label, so one fetch is one
 * snapshot.
 */
object HourlySnapshotRun {
    const val TAG = "HOURLY_SNAPSHOT"
    const val SAME_FETCH_MS = 5L * 60_000L

    interface Host {
        val sourceId: String

        /** The label of this source's newest snapshot at this site (same-site match), or null. */
        suspend fun lastSnapshotMs(): Long?

        suspend fun log(tag: String, message: String, level: String)
    }

    enum class Reason(val logValue: String) {
        FETCH("fetch"),
        SAME_FETCH("same_fetch"),
    }

    data class Decision(val labelMs: Long, val reason: Reason, val lastMs: Long?)

    fun decide(nowMs: Long, lastMs: Long?): Decision =
        if (lastMs != null && nowMs - lastMs in 0 until SAME_FETCH_MS) {
            Decision(lastMs, Reason.SAME_FETCH, lastMs)
        } else {
            Decision(nowMs, Reason.FETCH, lastMs)
        }

    /**
     * The label for [host]'s source now, logged. Fails open — a Host read that throws still saves the
     * snapshot; cancellation propagates.
     */
    suspend fun run(host: Host, nowMs: Long): Decision {
        val decision = try {
            decide(nowMs, host.lastSnapshotMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log(TAG, "source=${host.sourceId} decision=save reason=error error=${e::class.simpleName}:${e.message}", "WARN")
            return Decision(nowMs, Reason.FETCH, null)
        }
        host.log(TAG, line(host.sourceId, nowMs, decision), "VERBOSE")
        return decision
    }

    fun line(sourceId: String, nowMs: Long, d: Decision): String =
        "source=$sourceId decision=save reason=${d.reason.logValue}" +
            (d.lastMs?.let { " lastAgeMin=${(nowMs - it) / 60_000}" } ?: " last=none")
}
