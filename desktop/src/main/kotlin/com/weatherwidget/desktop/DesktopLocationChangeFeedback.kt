package com.weatherwidget.desktop

import com.weatherwidget.shared.util.LocationChangePaintPolicy

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
}
