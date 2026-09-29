package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.shared.util.LocationChangePaintPolicy
import java.time.LocalDate

/**
 * Desktop half of the setup-driven location change feedback (Android: `LocationUpdater`).
 *
 * A config save from the location picker to a *different* site returns the place name the popup
 * should announce ("Getting weather for {place}…") while the new site's first fetch is in flight.
 * Every other writer — Settings, the popup's own zoom/offset persistence, observations window —
 * returns null: they never move the site, and a jittered re-save of the same site is not a move.
 */
internal object DesktopLocationChangeFeedback {
    const val PICKER_SOURCE = "location-picker"

    fun pendingPlaceName(source: String, previous: DesktopConfig?, saved: DesktopConfig): String? {
        if (source != PICKER_SOURCE) return null
        val previousSite = previous?.let { it.lat to it.lon }
        if (LocationChangePaintPolicy.isSameSite(previousSite, saved.lat, saved.lon)) return null
        return saved.label
    }

    /**
     * What the popup shows while a picker save's fetch runs.
     * - The new site has a forecast row for today → draw its cache (`adoptCached`), no banner.
     * - It doesn't, but the previous site is on screen → keep that under the banner (user's call,
     *   2026-09-28: old data plus "Getting weather for…" beats a blank placeholder).
     * - Neither → the full-screen interstitial.
     * A non-null cache is not "drawable": a site last visited two weeks ago still loads its old
     * daily rows, none of them in the visible window — adopting that painted an empty graph.
     */
    fun decide(cached: ForecastSnapshot?, hasRenderOnScreen: Boolean, today: LocalDate): LocationChangePaintPolicy.Decision =
        // pendingPlaceName already established that the site changed.
        LocationChangePaintPolicy.decide(
            hasTodayRowAtNewSite = cached != null &&
                LocationChangePaintPolicy.hasDrawableCache(cached.raw.daily, cached.raw.hourly.map { it.dateTime }, today),
            hasRenderOnScreen = hasRenderOnScreen,
        )

    /** "Mountain View" / "the new location" — what the banner and interstitial call the site. */
    fun placePhrase(place: String?): String = place ?: "the new location"

    fun fetchingMessage(place: String?): String = "Getting weather for ${placePhrase(place)}\u2026"

    fun failedMessage(place: String?): String = "Couldn\u2019t get weather for ${placePhrase(place)}"
}

/**
 * The popup's location-change banner. [token] identifies the location change that owns it, so a
 * superseded change never clears or rewrites a newer one's banner.
 */
internal data class LocationBanner(val token: Any, val text: String, val failed: Boolean = false)
