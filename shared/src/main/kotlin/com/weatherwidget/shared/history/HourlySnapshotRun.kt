package com.weatherwidget.shared.history

import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether a fetch's hourly forecast is saved to `hourly_forecast_history`, and under which label — one
 * rule for both platforms (user, 2026-10-24; `performance/261024-hourly-snapshot-windows-by-view-and-battery.md`).
 * Replaced Android's `ForecastHistoryPolicy` (4 h windows for a displayed source, 12 h otherwise) and
 * desktop's 4 h windows for every source.
 *
 * No windows: a snapshot is labelled with its own fetch time, so the label never claims a forecast is
 * older than it is (a window labelled by its start did). Whether to save:
 *
 * | Source | Saved |
 * |---|---|
 * | Displayed | every fetch |
 * | Other, viewed in the last 8 days (`SourceFetchGate`) | when its last snapshot here is ≥ 11 h old (charging or ≥ 70 %), ≥ 22 h (< 70 %) |
 * | Other, not viewed in 8 days | never |
 *
 * The gaps sit a little under 12 / 24 h so snapshots follow the battery-driven fetch schedule instead
 * of creeping later each day. A skipped fetch writes nothing (a window overwrite still wrote every row).
 * A call within [SAME_FETCH_MS] of the source's last snapshot is the same fetch still being written
 * (the live rows, then the elapsed-hour backfill): it gets that snapshot's label, so one fetch is one
 * snapshot and the backfill never reads its own fetch as "recent".
 */
object HourlySnapshotRun {
    const val TAG = "HOURLY_SNAPSHOT"
    const val OTHER_GAP_HIGH_BATTERY_MS = 11L * 3_600_000L
    const val OTHER_GAP_LOW_BATTERY_MS = 22L * 3_600_000L
    const val SAME_FETCH_MS = 5L * 60_000L

    interface Host {
        val sourceId: String

        /** Shown now (Android: on any widget; desktop: the popup's source). */
        suspend fun isDisplayed(): Boolean

        /** In `SourceFetchGate`'s background set: viewed in the last 8 days, primary, or still in the grace period. */
        suspend fun viewedRecently(): Boolean

        /** Charging or battery ≥ 70 % (`ForecastHorizonCadence.isHighBattery`). */
        suspend fun batteryHigh(): Boolean

        /** The label of this source's newest snapshot at this site (same-site match), or null. */
        suspend fun lastSnapshotMs(): Long?

        suspend fun log(tag: String, message: String, level: String)
    }

    enum class Reason(val logValue: String) {
        SAME_FETCH("same_fetch"),
        DISPLAYED("displayed"),
        DUE("due"),
        RECENT("recent"),
        NOT_VIEWED("not_viewed"),
    }

    /** [labelMs] null = do not save this fetch's snapshot. */
    data class Decision(val labelMs: Long?, val reason: Reason, val lastMs: Long?, val gapMs: Long?) {
        val save: Boolean get() = labelMs != null
    }

    fun decide(
        nowMs: Long,
        displayed: Boolean,
        viewedRecently: Boolean,
        batteryHigh: Boolean,
        lastMs: Long?,
    ): Decision {
        if (lastMs != null && nowMs - lastMs in 0 until SAME_FETCH_MS) return Decision(lastMs, Reason.SAME_FETCH, lastMs, null)
        if (displayed) return Decision(nowMs, Reason.DISPLAYED, lastMs, null)
        if (!viewedRecently) return Decision(null, Reason.NOT_VIEWED, lastMs, null)
        val gap = if (batteryHigh) OTHER_GAP_HIGH_BATTERY_MS else OTHER_GAP_LOW_BATTERY_MS
        return if (lastMs != null && nowMs - lastMs < gap) {
            Decision(null, Reason.RECENT, lastMs, gap)
        } else {
            Decision(nowMs, Reason.DUE, lastMs, gap)
        }
    }

    /**
     * The decision for [host]'s source now, logged. Fails open — a Host read that throws saves the
     * snapshot, as before this rule existed; cancellation still propagates.
     */
    suspend fun run(host: Host, nowMs: Long): Decision {
        val decision = try {
            val lastMs = host.lastSnapshotMs()
            if (lastMs != null && nowMs - lastMs in 0 until SAME_FETCH_MS) {
                Decision(lastMs, Reason.SAME_FETCH, lastMs, null)
            } else {
                val displayed = host.isDisplayed()
                decide(
                    nowMs = nowMs,
                    displayed = displayed,
                    viewedRecently = displayed || host.viewedRecently(),
                    batteryHigh = displayed || host.batteryHigh(),
                    lastMs = lastMs,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log(TAG, "source=${host.sourceId} decision=save reason=error error=${e::class.simpleName}:${e.message}", "WARN")
            return Decision(nowMs, Reason.DUE, null, null)
        }
        host.log(TAG, line(host.sourceId, nowMs, decision), level(decision))
        return decision
    }

    fun line(sourceId: String, nowMs: Long, d: Decision): String =
        "source=$sourceId decision=${if (d.save) "save" else "skip"} reason=${d.reason.logValue}" +
            (d.lastMs?.let { " lastAgeMin=${(nowMs - it) / 60_000}" } ?: " last=none") +
            (d.gapMs?.let { " gapH=${it / 3_600_000}" } ?: "")

    /** Steady state is a save of the displayed source on every fetch: VERBOSE. A decision for another source is INFO. */
    fun level(d: Decision): String = when (d.reason) {
        Reason.DISPLAYED, Reason.SAME_FETCH -> "VERBOSE"
        else -> "INFO"
    }
}
