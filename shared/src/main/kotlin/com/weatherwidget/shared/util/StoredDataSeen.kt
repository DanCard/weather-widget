package com.weatherwidget.shared.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex

/**
 * What stored-data version a screen last loaded at, so it can tell on return that it is stale.
 *
 * A screen that loads once and starts another (Settings → Refresh data) kept showing the rows it had
 * loaded before: Fold 4 2026-11-13, Forecast Comparison' Age column read `1h` two minutes after a refresh
 * of every source (plans/261113-android-data-screens-reload-after-settings-refresh.md). [markLoaded] is
 * called before the load reads anything — the rule [VersionedTtlCache] uses — so a write landing
 * mid-load still reads as a change and the screen reloads once more instead of trusting half-stale rows.
 * Process-local, like [StoredDataVersion]: Android's worker and activities share a process.
 *
 * [reloads] also covers a write landing while the screen stays open: Fold 4 2026-11-16, Forecast
 * Comparison read Google 0.5 s and Open-Meteo's retry 75 s before they were stored and showed Age `3h`
 * until left (plans/261116-comparison-screen-reloads-while-open.md).
 */
class StoredDataSeen(private val version: StoredDataVersion = StoredDataVersion.process) {
    @Volatile
    private var seen: Long = NEVER

    /** Why a [reloads] emission fired: stored data changed while away, or while on screen. */
    enum class Trigger(val logValue: String) { RESUME("resume"), LIVE("live") }

    /** Call before a load reads: the version it will be showing at least. */
    fun markLoaded() {
        seen = version.current()
    }

    /** Whether stored data was written since [markLoaded]; true before the first load. */
    fun changedSinceLoad(): Boolean = seen == NEVER || version.current() != seen

    /**
     * Collect while the screen is shown (Android `repeatOnLifecycle(RESUMED)`). Emits [Trigger.RESUME] at
     * once when stored data changed while the screen was away, then [Trigger.LIVE] for each later write
     * that the screen has not loaded at. Not debounced (user, 2026-11-16): each source shows as it lands.
     * Never emits before the first load, which the screen runs itself.
     */
    fun reloads(): Flow<Trigger> =
        version.changes.withIndex()
            .filter { seen != NEVER && it.value != seen }
            .map { if (it.index == 0) Trigger.RESUME else Trigger.LIVE }

    companion object {
        private const val NEVER = -1L

        /** The sparse INFO line a screen logs when it reloads on return: one per reload, never per resume. */
        const val LOG_TAG = "SCREEN_RELOAD"

        fun logMessage(screen: String) = "screen=$screen reason=stored_data_changed"

        fun logMessage(screen: String, trigger: Trigger) = "${logMessage(screen)} trigger=${trigger.logValue}"
    }
}
