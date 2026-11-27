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
     * Adds [newLocation] to [recents]. If an existing item of the same kind (fixed vs follow-device)
     * matches it, it is removed and [newLocation] is placed at the front (index 0). Fixed entries match
     * on coordinates (within ~1.1km / 0.01 deg) or label (case-insensitive); there is only ever one
     * follow-device entry, so a new one replaces it wherever the fix was.
     *
     * [leaving] is the setting being switched away from: its entry keeps its position but its
     * timestamp becomes [nowMs], so "last used" means when the user was last there rather than when
     * they first picked it (the previous-location toggle's 14-day window reads it).
     * The returned list is capped at [maxEntries].
     */
    fun addRecent(
        recents: List<RecentLocation>,
        newLocation: RecentLocation,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        leaving: CurrentLocationSetting? = null,
        nowMs: Long = newLocation.timestamp,
    ): List<RecentLocation> {
        val stamped = if (leaving == null) recents else recents.map {
            if (PreviousLocationToggle.isCurrent(it, leaving)) it.copy(timestamp = nowMs) else it
        }
        val filtered = stamped.filterNot { isMatch(it, newLocation) }
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
     * Filters [recents] by [query] for the location pickers' dropdown. Blank query returns the full
     * list. Follow-device entries are left out: picking one there would pin an old fix, and the
     * "Use device location" button beside the dropdown is the way back to following the device.
     */
    fun filterMatching(
        recents: List<RecentLocation>,
        query: String,
    ): List<RecentLocation> {
        val places = recents.filterNot { it.followDevice }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return places
        return places.filter { it.label.contains(trimmed, ignoreCase = true) }
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
        if (a.followDevice != b.followDevice) return false
        if (a.followDevice) return true
        val coordsMatch = sameCoordinates(a.lat, a.lon, b.lat, b.lon)
        val labelMatch = a.label.isNotBlank() && b.label.isNotBlank() && a.label.equals(b.label, ignoreCase = true)
        return coordsMatch || labelMatch
    }

    /** The recents' coordinate box (~1.1 km): the same place for dedupe and for "is this the current location". */
    fun sameCoordinates(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean =
        abs(aLat - bLat) < 0.01 && abs(aLon - bLon) < 0.01
}
