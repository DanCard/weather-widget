package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource

/**
 * Pure logic for the Settings "API Sources" list — the visible+hidden ordering, the toggle
 * guard, and the up/down reorder. Extracted from `SettingsActivity.rebuildSourceRows`
 * (`app/.../SettingsActivity.kt:260-341`) and `SettingsWindow.ApiSourcesList`
 * (`desktop/.../SettingsWindow.kt:252-337`) so both platforms stop duplicating it.
 *
 * Works in `List<String>` of `WeatherSource.id` because both persistence layers store the
 * visible list that way (`WidgetStateManager.KEY_VISIBLE_SOURCES_ORDER` as CSV on Android;
 * `DesktopConfig.visibleSources: List<String>` on desktop). Use [ordered] when rendering —
 * it returns the visible sources in their stored order followed by hidden sources in
 * [ALL_CONFIGURABLE] order.
 *
 * Phase 1 of the desktop settings parity plan (`plans/260727-desktop-settings-parity-with-android.md`).
 */
object WeatherSourceOrdering {

    /**
     * Every source the user can enable in Settings. Excludes [WeatherSource.GENERIC_GAP]
     * (synthetic climate-normal fallback) and deprecated providers retained only for historical
     * data parsing. Order is the canonical hidden-source display order.
     *
     * Mirrors `SettingsActivity.allSources` and `SettingsWindow.ApiSourcesList.allSources`.
     */
    val ALL_CONFIGURABLE: List<WeatherSource> = listOf(
        WeatherSource.NWS,
        WeatherSource.GOOGLE_WEATHER,
        WeatherSource.TOMORROW_IO,
        WeatherSource.OPEN_METEO,
        WeatherSource.SILURIAN,
        WeatherSource.WEATHER_API,
        WeatherSource.OPEN_WEATHER_MAP,
    )

    /** Appends [source] unless it is already enabled. A starting position: the user can reorder afterwards. */
    fun withEnabled(ids: List<String>, source: WeatherSource): List<String> =
        if (source.id in ids) ids else ids + source.id

    /**
     * What a display (an Android widget, the desktop popup) shows after the enabled list changes to
     * [newIds]: [selectedId] if still enabled, else the new primary.
     */
    fun selectionAfterChange(newIds: List<String>, selectedId: String?): String =
        selectedId?.takeIf { it in newIds } ?: newIds.first()

    /** The default visible-source list on a fresh install (mirrors both platforms' defaults). */
    val DEFAULT_VISIBLE_IDS: List<String> = listOf(
        WeatherSource.NWS.id,
        WeatherSource.OPEN_METEO.id,
        WeatherSource.SILURIAN.id,
    )

    /**
     * Canonicalizes a persisted visible-source list. Unknown, duplicate, synthetic, and deprecated
     * ids are removed. A completely invalid list falls back to [DEFAULT_VISIBLE_IDS] so callers
     * never expose an empty source cycle.
     */
    fun sanitizeVisibleIds(
        visibleIds: List<String>,
        fallbackIds: List<String> = DEFAULT_VISIBLE_IDS,
    ): List<String> {
        val configurableIds = ALL_CONFIGURABLE.mapTo(linkedSetOf()) { it.id }
        val sanitized = visibleIds
            .map(String::trim)
            .filter { it in configurableIds }
            .distinct()
        if (sanitized.isNotEmpty()) return sanitized

        return fallbackIds
            .map(String::trim)
            .filter { it in configurableIds }
            .distinct()
            .ifEmpty { listOf(WeatherSource.OPEN_METEO.id) }
    }

    /**
     * Returns the full source list for rendering: visible sources first (in their stored order),
     * then hidden sources in [ALL_CONFIGURABLE] order. Unknown ids in [visibleIds] are dropped,
     * matching both platforms' existing behavior.
     */
    fun ordered(visibleIds: List<String>): List<WeatherSource> {
        val sanitized = sanitizeVisibleIds(visibleIds)
        val visible = sanitized.mapNotNull { id -> ALL_CONFIGURABLE.find { it.id == id } }
        return visible + available(sanitized)
    }

    /**
     * The sources Settings lists under "Available": every configurable source not in [visibleIds],
     * in [ALL_CONFIGURABLE] order. The hidden half of [ordered].
     */
    fun available(visibleIds: List<String>): List<WeatherSource> {
        val sanitized = sanitizeVisibleIds(visibleIds)
        return ALL_CONFIGURABLE.filter { it.id !in sanitized }
    }

    /**
     * Returns the list with the source at position [from] moved to position [to] — what one drag in
     * Settings produces. No-op (returns the sanitized input) if either index is out of range.
     */
    fun move(visibleIds: List<String>, from: Int, to: Int): List<String> {
        val current = sanitizeVisibleIds(visibleIds).toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return current
        current.add(to, current.removeAt(from))
        return current
    }

    /**
     * Returns the new visible list after toggling [source], or **null** if the toggle would
     * empty the list (the "must keep at least one source" case). Callers should show the
     * platform's "keep one" message (toast/snackbar) when this returns null.
     *
     * - [makeVisible] = true: adds [source] if not already present, placed by [withEnabled].
     * - [makeVisible] = false: removes [source] unless that would leave an empty list.
     */
    fun toggle(visibleIds: List<String>, source: WeatherSource, makeVisible: Boolean): List<String>? {
        if (source !in ALL_CONFIGURABLE) return sanitizeVisibleIds(visibleIds)
        val current = sanitizeVisibleIds(visibleIds).toMutableList()
        return when (makeVisible) {
            true -> withEnabled(current, source)
            false -> {
                if (current.size <= 1) return null
                current.remove(source.id)
                current
            }
        }
    }

    /**
     * Returns the new list with [source] moved one slot earlier. No-op (returns the input
     * unchanged) if [source] is already at the top or not in the list.
     */
    fun moveUp(visibleIds: List<String>, source: WeatherSource): List<String> {
        val sanitized = sanitizeVisibleIds(visibleIds)
        val pos = sanitized.indexOf(source.id)
        if (pos <= 0) return sanitized
        val current = sanitized.toMutableList()
        current[pos] = current[pos - 1]
        current[pos - 1] = source.id
        return current
    }

    /**
     * Returns the new list with [source] moved one slot later. No-op (returns the input
     * unchanged) if [source] is already at the bottom or not in the list.
     */
    fun moveDown(visibleIds: List<String>, source: WeatherSource): List<String> {
        val sanitized = sanitizeVisibleIds(visibleIds)
        val pos = sanitized.indexOf(source.id)
        if (pos < 0 || pos >= sanitized.size - 1) return sanitized
        val current = sanitized.toMutableList()
        current[pos] = current[pos + 1]
        current[pos + 1] = source.id
        return current
    }
}
