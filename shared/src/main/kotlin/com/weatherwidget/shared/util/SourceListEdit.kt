package com.weatherwidget.shared.util

/**
 * Adding, removing or reordering sources in Settings fetches nothing, on Android and desktop
 * alike. The list edit is a preference; a source is fetched when the user switches to it
 * ([SourceToggleRefreshPolicy]) or on its own cadence. A removal that moves the displayed source
 * to the next one is still an edit, not a switch.
 *
 * The one exception is the shared "nothing to draw" arm: when the source now shown has no cached
 * forecast at all, it fetches at once ([fetchesForecast]).
 *
 * Desktop used to treat any change to the visible list, a reorder included, like a location change
 * and refetch a forecast older than 15 minutes: one Settings drag cost Google eight
 * `forecast/hours` pages for the source already on screen (2026-11-18,
 * `plans/261118-source-list-edits-never-fetch.md`).
 *
 * Works in `WeatherSource.id` lists, like [WeatherSourceOrdering].
 */
object SourceListEdit {
    const val LOG_TAG = "SOURCE_LIST_EDIT"

    data class Change(
        val added: List<String>,
        val removed: List<String>,
        /** True when the sources kept in both lists changed relative order. */
        val reordered: Boolean,
    ) {
        /** `add`, `remove`, `reorder`, or a `+`-joined mix when one save did several. */
        val kind: String
            get() = buildList {
                if (added.isNotEmpty()) add("add")
                if (removed.isNotEmpty()) add("remove")
                if (reordered) add("reorder")
            }.joinToString("+")
    }

    /** The edit from [before] to [after], or null when the lists are identical. */
    fun classify(before: List<String>, after: List<String>): Change? {
        if (before == after) return null
        val beforeSet = before.toSet()
        val afterSet = after.toSet()
        val kept = before.filter { it in afterSet }
        return Change(
            added = after.filter { it !in beforeSet },
            removed = before.filter { it !in afterSet },
            reordered = kept != after.filter { it in beforeSet },
        )
    }

    /** Whether an edit fetches the shown source's forecast: only when it has nothing to draw. */
    fun fetchesForecast(cachePresent: Boolean): Boolean = !cachePresent

    /**
     * The `SOURCE_LIST_EDIT` message both platforms log for [change], at save time. Whether the
     * shown source then had nothing to draw is the platform's own later line (desktop
     * `LAUNCH_REFRESH_CHECK reason=source_list_edit`, Android's render-time staleness probe).
     */
    fun logMessage(platform: String, change: Change, after: List<String>): String =
        "platform=$platform kind=${change.kind} added=${change.added} removed=${change.removed} order=$after"
}
