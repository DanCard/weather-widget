package com.weatherwidget.shared.util

import com.weatherwidget.data.model.RecentLocation

/**
 * The location setting in force right now: a pinned place, or following the device (Android only).
 * [lat]/[lon] are null when no location is set yet.
 */
data class CurrentLocationSetting(
    val lat: Double?,
    val lon: Double?,
    val followDevice: Boolean,
)

/**
 * Settings → Location's "↔ San Francisco" button: one tap back to the previous location setting
 * (plans/261126-previous-location-toggle.md). Owns the whole rule for both platforms: which recent
 * entry is the target, when the button shows, and the recents/timestamp bookkeeping of a switch.
 * Platforms only render the button and run their normal "location chosen" save path.
 */
object PreviousLocationToggle {
    private const val TAG = "LocationToggle"

    /** Hidden once the target was last used, and the toggle last pressed, longer ago than this. */
    const val VISIBILITY_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    /** True when [entry] is the setting [current] describes, so switching to it would change nothing. */
    fun isCurrent(entry: RecentLocation, current: CurrentLocationSetting): Boolean {
        if (entry.followDevice) return current.followDevice
        val lat = current.lat ?: return false
        val lon = current.lon ?: return false
        return RecentLocationsHelper.sameCoordinates(entry.lat, entry.lon, lat, lon)
    }

    /**
     * The entry the toggle switches to, or null when the button is hidden. The target is the newest
     * recent that is not the current setting; it shows while that entry was used within
     * [VISIBILITY_WINDOW_MS] or the toggle itself was pressed within it.
     *
     * [supportsFollowDevice] is false on desktop, which has no location mode: a follow-device entry
     * is skipped there rather than offered.
     */
    fun target(
        recents: List<RecentLocation>,
        current: CurrentLocationSetting,
        lastToggleUsedAtMs: Long?,
        nowMs: Long,
        supportsFollowDevice: Boolean = true,
    ): RecentLocation? {
        val candidate = recents.firstOrNull {
            (supportsFollowDevice || !it.followDevice) && !isCurrent(it, current)
        }
        if (candidate == null) {
            Log.v(TAG, "hidden: no previous setting among ${recents.size} recents")
            return null
        }
        val targetAgeMs = nowMs - candidate.timestamp
        val toggleAgeMs = lastToggleUsedAtMs?.let { nowMs - it }
        val visible = targetAgeMs <= VISIBILITY_WINDOW_MS ||
            (toggleAgeMs != null && toggleAgeMs <= VISIBILITY_WINDOW_MS)
        Log.v(
            TAG,
            "${if (visible) "shown" else "hidden: stale"} target=\"${candidate.label}\" " +
                "followDevice=${candidate.followDevice} targetAgeH=${targetAgeMs / 3_600_000} " +
                "toggleAgeH=${toggleAgeMs?.div(3_600_000)}",
        )
        return candidate.takeIf { visible }
    }

    /** Recents after the toggle switches from [current] to [target]: [target] first, [current]'s entry stamped. */
    fun recentsAfterSwitch(
        recents: List<RecentLocation>,
        current: CurrentLocationSetting,
        target: RecentLocation,
        nowMs: Long,
    ): List<RecentLocation> =
        RecentLocationsHelper.addRecent(
            recents = recents,
            newLocation = target.copy(timestamp = nowMs),
            leaving = current,
            nowMs = nowMs,
        )

    /**
     * Short place name for the button. Drops the street part of an address, whether written
     * "860 Avery Dr, Mountain View, CA 94043" or Nominatim's "860, Avery Drive, Mountain View, …",
     * leaves out postal-code-only segments, and keeps at most [MAX_LABEL_SEGMENTS] parts. A label with
     * nothing left after that is returned as stored.
     */
    fun shortLabel(label: String): String {
        var segments = label.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val first = segments.firstOrNull() ?: return label
        if (segments.size >= 2 && first.firstOrNull()?.isDigit() == true) {
            // A bare house number is followed by its street; "860 Avery Dr" carries both.
            segments = segments.drop(if (first.all { it.isDigit() } && segments.size >= 3) 2 else 1)
        }
        val place = segments.filterNot { seg -> seg.all { it.isDigit() || it == '-' || it == ' ' } }
            .take(MAX_LABEL_SEGMENTS)
        return if (place.isEmpty()) label else place.joinToString(", ")
    }

    private const val MAX_LABEL_SEGMENTS = 3

    /** The breadcrumb both platforms log when the toggle is pressed. */
    fun usedLogLine(current: CurrentLocationSetting, target: RecentLocation, lastToggleUsedAtMs: Long?): String =
        "LOCATION_TOGGLE from=" +
            (if (current.followDevice) "follow-device" else "${current.lat},${current.lon}") +
            " to=" + (if (target.followDevice) "follow-device" else "${target.lat},${target.lon}") +
            " label=\"${target.label}\" lastToggleUsedAt=$lastToggleUsedAtMs"
}
