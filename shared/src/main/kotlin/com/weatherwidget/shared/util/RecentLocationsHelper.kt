package com.weatherwidget.shared.util

import com.weatherwidget.data.model.RecentLocation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

object RecentLocationsHelper {
    const val DEFAULT_MAX_ENTRIES = 8

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * Adds [newLocation] to [recents]. If an existing item has matching coordinates
     * (within ~1.1km / 0.01 deg) or matching label (case-insensitive), it is removed
     * and the updated [newLocation] is placed at the front (index 0).
     * The returned list is capped at [maxEntries].
     */
    fun addRecent(
        recents: List<RecentLocation>,
        newLocation: RecentLocation,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): List<RecentLocation> {
        val filtered = recents.filterNot { isMatch(it, newLocation) }
        return (listOf(newLocation) + filtered).take(maxEntries)
    }

    /**
     * Removes an entry matching [target] by proximity or label.
     */
    fun removeRecent(
        recents: List<RecentLocation>,
        target: RecentLocation,
    ): List<RecentLocation> = recents.filterNot { isMatch(it, target) }

    /**
     * Filters [recents] by [query]. Blank query returns the full list.
     */
    fun filterMatching(
        recents: List<RecentLocation>,
        query: String,
    ): List<RecentLocation> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return recents
        return recents.filter { it.label.contains(trimmed, ignoreCase = true) }
    }

    fun encodeToJson(recents: List<RecentLocation>): String =
        json.encodeToString(recents)

    fun decodeFromJson(raw: String?): List<RecentLocation> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<RecentLocation>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isMatch(a: RecentLocation, b: RecentLocation): Boolean {
        val coordsMatch = abs(a.lat - b.lat) < 0.01 && abs(a.lon - b.lon) < 0.01
        val labelMatch = a.label.isNotBlank() && b.label.isNotBlank() && a.label.equals(b.label, ignoreCase = true)
        return coordsMatch || labelMatch
    }
}
