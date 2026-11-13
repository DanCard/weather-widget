package com.weatherwidget.shared.util

/**
 * What stored-data version a screen last loaded at, so it can tell on return that it is stale.
 *
 * A screen that loads once and starts another (Settings → Refresh data) kept showing the rows it had
 * loaded before: Fold 4 2026-11-13, History of Forecasts' Age column read `1h` two minutes after a refresh
 * of every source (plans/261113-android-data-screens-reload-after-settings-refresh.md). [markLoaded] is
 * called before the load reads anything — the rule [VersionedTtlCache] uses — so a write landing
 * mid-load still reads as a change and the screen reloads once more instead of trusting half-stale rows.
 * Process-local, like [StoredDataVersion]: Android's worker and activities share a process.
 */
class StoredDataSeen(private val version: StoredDataVersion = StoredDataVersion.process) {
    private var seen: Long = NEVER

    /** Call before a load reads: the version it will be showing at least. */
    fun markLoaded() {
        seen = version.current()
    }

    /** Whether stored data was written since [markLoaded]; true before the first load. */
    fun changedSinceLoad(): Boolean = seen == NEVER || version.current() != seen

    companion object {
        private const val NEVER = -1L

        /** The sparse INFO line a screen logs when it reloads on return: one per reload, never per resume. */
        const val LOG_TAG = "SCREEN_RELOAD"

        fun logMessage(screen: String) = "screen=$screen reason=stored_data_changed"
    }
}
