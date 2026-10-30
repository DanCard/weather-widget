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
 */
object SettingsRefreshAllSources {
    const val TAG = "SETTINGS_REFRESH"

    /** The sources a settings refresh covers: [displayedId] first, then the rest of [usableIds]. */
    fun order(displayedId: String, usableIds: List<String>): List<String> =
        (listOf(displayedId) + usableIds).distinct()

    /** Whether a forced sync skips the recently-viewed gate: an untargeted all-sources refresh. */
    fun bypassesViewGate(allSources: Boolean, targetSourceId: String?): Boolean =
        allSources && targetSourceId == null

    data class Outcome(val refreshed: List<String>, val failed: Map<String, String>)

    /**
     * Every source in [order] after the displayed one, one at a time, through [refresh] (a forced
     * daily + hourly fetch that throws on failure). One source failing is logged and the rest still
     * run; cancellation propagates.
     */
    suspend fun refreshOthers(
        displayedId: String,
        usableIds: List<String>,
        refresh: suspend (sourceId: String) -> Unit,
        log: (tag: String, message: String, level: String) -> Unit,
    ): Outcome {
        val others = order(displayedId, usableIds).drop(1)
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
