package com.weatherwidget.desktop

import com.weatherwidget.shared.util.SettingsRefreshAllSources

/**
 * The daemon fetch requests a user refresh sends, built once for Settings → Refresh data
 * (`DesktopUiApplication`) and the command line (`DesktopCli`), so the two cannot drift
 * (plans/261124-desktop-cli-refresh-hook.md).
 */
internal object DesktopRefreshRequests {

    /**
     * The displayed source's forecast. [allSources]: a refresh of every source (Settings → Refresh
     * data, the command line), which also fills the 7-day observation window inline.
     */
    fun displayedForecast(
        sourceId: String,
        lat: Double,
        lon: Double,
        origin: String,
        allSources: Boolean,
        reason: String = "user_refresh:$origin",
    ) = FetchRequest(
        FetchRequest.Kind.FORECAST, sourceId, lat, lon, origin, reason,
        userRefresh = true, observationWindow = allSources,
    )

    /** Every other source in [sourceIds]; null = every usable source. */
    fun otherSources(
        displaySource: String,
        lat: Double,
        lon: Double,
        origin: String,
        sourceIds: List<String>?,
        reason: String = "user_refresh:$origin",
    ) = FetchRequest(
        FetchRequest.Kind.OTHER_SOURCES, displaySource, lat, lon, origin, reason,
        userRefresh = true, sourceIds = sourceIds,
    )

    /**
     * Settings → Refresh data: the sources [partition] found stale — the displayed one first when it
     * is among them, then the rest. Empty when every source is fresh.
     */
    fun settingsRefresh(config: DesktopConfig, partition: SettingsRefreshAllSources.Partition, origin: String): List<FetchRequest> {
        val others = partition.fetch.filter { it != config.displaySource }
        return listOfNotNull(
            displayedForecast(config.displaySource, config.lat, config.lon, origin, allSources = true)
                .takeIf { config.displaySource in partition.fetch },
            otherSources(config.displaySource, config.lat, config.lon, origin, others).takeIf { others.isNotEmpty() },
        )
    }

    /** Every usable source, forced (billed), as Android's debug `FULL_SYNC`. */
    fun fullSync(config: DesktopConfig, origin: String): List<FetchRequest> {
        val reason = "debug_full_sync:$origin"
        return listOf(
            displayedForecast(config.displaySource, config.lat, config.lon, origin, allSources = true, reason = reason),
            otherSources(config.displaySource, config.lat, config.lon, origin, sourceIds = null, reason = reason),
        )
    }
}
