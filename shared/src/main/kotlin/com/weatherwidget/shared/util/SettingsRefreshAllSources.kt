package com.weatherwidget.shared.util

import kotlinx.coroutines.CancellationException

/**
 * Settings → Refresh data fetches every usable source, not only the displayed one (user,
 * 2026-10-29: desktop refreshed NWS alone). The displayed source goes first, so its graph is
 * published before the rest; the others follow in the usable order. The recently-viewed gate
 * ([com.weatherwidget.shared.sourceview.SourceFetchGate]) does not apply: the user asked for all.
 *
 * Android's worker fetches every source in one forced sync, so it takes only [bypassesViewGate];
 * desktop's repositories are per source, so it runs [refreshOthers] after the displayed refresh.
 * [refreshOthers] takes plain functions, not a Host port: it stores nothing, so there is no
 * per-platform SQL for a contract suite to pin.
 *
 * A source fetched or hand-refreshed within [FRESH_SKIP_MS] is skipped ([partition], user 2026-11-13:
 * NWS refetched 75 s after the popup fetched it). Both platforms partition once, at the click; when
 * nothing is left the button shows "Already up to date" ([RefreshNotice.Reason.ALL_FRESH]).
 * plans/261113-settings-refresh-skips-fresh-sources.md.
 */
object SettingsRefreshAllSources {
    const val TAG = "SETTINGS_REFRESH"

    /** The single-source refresh throttle's window ([UserRefreshThrottle]), so both rules agree on "recent". */
    const val FRESH_SKIP_MS = UserRefreshThrottle.THROTTLE_MS

    /** [fetch] in refresh order; [fresh] = skipped source → how long ago it was last fetched or refreshed. */
    data class Partition(val fetch: List<String>, val fresh: Map<String, Long>) {
        val allFresh: Boolean get() = fetch.isEmpty()

        /** The `SETTINGS_REFRESH` row both platforms write at the click. */
        fun logLine(): String =
            "partition fetch=${fetch.joinToString(",").ifEmpty { "none" }} " +
                "skip_fresh=${fresh.entries.joinToString(",") { (id, ageMs) -> "$id(${ageMs / 1000}s)" }.ifEmpty { "none" }}"
    }

    /**
     * Splits [order] (from [order]) into sources to fetch and sources still fresh: last fetch or last
     * user refresh under [FRESH_SKIP_MS] ago. A source with neither, or with a time in the future
     * (clock change), is fetched.
     */
    fun partition(
        order: List<String>,
        lastFetchMs: (sourceId: String) -> Long?,
        lastUserRefreshMs: (sourceId: String) -> Long?,
        nowMs: Long,
    ): Partition {
        val fetch = mutableListOf<String>()
        val fresh = LinkedHashMap<String, Long>()
        for (id in order) {
            val ageMs = UserRefreshThrottle.latest(lastFetchMs(id), lastUserRefreshMs(id), nowMs)?.let { nowMs - it }
            if (ageMs != null && ageMs < FRESH_SKIP_MS) fresh[id] = ageMs else fetch += id
        }
        return Partition(fetch, fresh)
    }

    /** The sources a settings refresh covers: [displayedId] first, then the rest of [usableIds]. */
    fun order(displayedId: String, usableIds: List<String>): List<String> =
        (listOf(displayedId) + usableIds).distinct()

    /** Whether a forced sync skips the recently-viewed gate: an untargeted all-sources refresh. */
    fun bypassesViewGate(allSources: Boolean, targetSourceId: String?): Boolean =
        allSources && targetSourceId == null

    data class Outcome(val refreshed: List<String>, val failed: Map<String, String>)

    /**
     * Each of [sourceIds] (the click's [Partition.fetch] less the displayed source), one at a time,
     * through [refresh] (a forced daily + hourly fetch that throws on failure). One source failing is logged and the rest still
     * run; cancellation propagates. [onFailure] hears every failed source, so the platform can arm its
     * retry ([SourceRetry.onFailure] with `requested = true`): the user asked for this source.
     */
    suspend fun refreshOthers(
        displayedId: String,
        sourceIds: List<String>,
        refresh: suspend (sourceId: String) -> Unit,
        log: (tag: String, message: String, level: String) -> Unit,
        onFailure: suspend (sourceId: String, error: Exception) -> Unit = { _, _ -> },
    ): Outcome {
        val others = sourceIds.filter { it != displayedId }.distinct()
        log(TAG, "start displayed=$displayedId others=${others.joinToString(",").ifEmpty { "none" }}", "INFO")
        val refreshed = mutableListOf<String>()
        val failed = LinkedHashMap<String, String>()
        for (sourceId in others) {
            try {
                refresh(sourceId)
                refreshed += sourceId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[sourceId] = "${e::class.simpleName}: ${e.message}"
                log(TAG, "source=$sourceId failed ${e::class.simpleName}: ${e.message}", "WARN")
                onFailure(sourceId, e)
            }
        }
        log(
            TAG,
            "done refreshed=${refreshed.joinToString(",").ifEmpty { "none" }} " +
                "failed=${failed.keys.joinToString(",").ifEmpty { "none" }}",
            if (failed.isEmpty()) "INFO" else "WARN",
        )
        return Outcome(refreshed, failed)
    }
}
