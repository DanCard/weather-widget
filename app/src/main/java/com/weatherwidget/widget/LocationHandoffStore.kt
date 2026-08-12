package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.util.SharedPreferencesUtil

internal data class HandoffLocation(
    val lat: Double,
    val lon: Double,
    val label: String,
)

internal data class CandidateLocation(
    val location: HandoffLocation,
    val firstSeenMs: Long,
)

internal enum class CandidateProposal {
    RETURNED_TO_ACTIVE,
    SAME_ACTIVE,
    SAME_CANDIDATE,
    UPDATED,
}

/**
 * Persists the latest follow-device candidate without changing the coordinates used for display.
 * The existing per-widget coordinates remain the active/last-useful location until promotion.
 */
internal object LocationHandoffStore {
    private const val PREFS_NAME = "weather_prefs"
    private const val KEY_LAT = "location_candidate_lat"
    private const val KEY_LON = "location_candidate_lon"
    private const val KEY_LABEL = "location_candidate_label"
    private const val KEY_FIRST_SEEN_MS = "location_candidate_first_seen_ms"

    @Synchronized
    fun getCandidate(context: Context): CandidateLocation? {
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        val label = prefs.getString(KEY_LABEL, null) ?: String.format("%.4f, %.4f", lat, lon)
        val firstSeenMs = prefs.getLong(KEY_FIRST_SEEN_MS, 0L)
        if (firstSeenMs <= 0L) return null
        return CandidateLocation(HandoffLocation(lat, lon, label), firstSeenMs)
    }

    @Synchronized
    /**
     * [activeLocation] is null when the app has no location at all. A fresh fix can then never be
     * "the same site we already show", so it is always worth proposing as a candidate — which is
     * precisely the case the GPS auto-heal exists to serve.
     */
    fun propose(
        context: Context,
        activeLocation: Pair<Double, Double>?,
        freshLocation: HandoffLocation,
        nowMs: Long,
    ): CandidateProposal {
        val existing = getCandidate(context)
        if (activeLocation != null && LocationMatch.sameSite(
                activeLocation.first,
                activeLocation.second,
                freshLocation.lat,
                freshLocation.lon,
            )
        ) {
            if (existing != null) {
                clear(context)
                return CandidateProposal.RETURNED_TO_ACTIVE
            }
            return CandidateProposal.SAME_ACTIVE
        }
        if (existing != null && LocationMatch.sameSite(
                existing.location.lat,
                existing.location.lon,
                freshLocation.lat,
                freshLocation.lon,
            )
        ) {
            return CandidateProposal.SAME_CANDIDATE
        }

        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit()
            .putString(KEY_LAT, freshLocation.lat.toString())
            .putString(KEY_LON, freshLocation.lon.toString())
            .putString(KEY_LABEL, freshLocation.label)
            .putLong(KEY_FIRST_SEEN_MS, nowMs)
            .commit()
        return CandidateProposal.UPDATED
    }

    @Synchronized
    fun clear(context: Context) {
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).edit()
            .remove(KEY_LAT)
            .remove(KEY_LON)
            .remove(KEY_LABEL)
            .remove(KEY_FIRST_SEEN_MS)
            .commit()
    }

    @Synchronized
    fun promoteIfMatches(
        context: Context,
        expected: CandidateLocation,
        promote: (HandoffLocation) -> Unit,
    ): Boolean {
        val current = getCandidate(context) ?: return false
        if (!matches(current, expected)) return false
        promote(current.location)
        clear(context)
        return true
    }

    fun matches(first: CandidateLocation, second: CandidateLocation): Boolean =
        first.firstSeenMs == second.firstSeenMs &&
            LocationMatch.sameSite(
                first.location.lat,
                first.location.lon,
                second.location.lat,
                second.location.lon,
            )
}
