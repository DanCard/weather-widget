package com.weatherwidget.desktop

import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.shared.util.CurrentLocationSetting
import com.weatherwidget.shared.util.PreviousLocationToggle

/**
 * Desktop host for Settings' previous-location toggle (plans/261126-previous-location-toggle.md).
 * The rule lives in [PreviousLocationToggle]; this maps [DesktopConfig] onto it. The desktop has no
 * location mode, so the current setting is always the pinned config location and follow-device
 * entries are never offered.
 */
internal object DesktopLocationToggle {
    fun currentSetting(config: DesktopConfig): CurrentLocationSetting =
        CurrentLocationSetting(lat = config.lat, lon = config.lon, followDevice = false)

    fun target(config: DesktopConfig, nowMs: Long): RecentLocation? =
        PreviousLocationToggle.target(
            recents = config.recentLocations,
            current = currentSetting(config),
            lastToggleUsedAtMs = config.locationToggleUsedAtMs,
            nowMs = nowMs,
            supportsFollowDevice = false,
        )

    /** [config] moved to [target], saved with [DesktopLocationChangeFeedback.TOGGLE_SOURCE]. */
    fun switched(config: DesktopConfig, target: RecentLocation, nowMs: Long): DesktopConfig =
        config.copy(
            lat = target.lat,
            lon = target.lon,
            label = target.label,
            recentLocations = PreviousLocationToggle.recentsAfterSwitch(
                recents = config.recentLocations,
                current = currentSetting(config),
                target = target,
                nowMs = nowMs,
            ),
            locationToggleUsedAtMs = nowMs,
        )
}
