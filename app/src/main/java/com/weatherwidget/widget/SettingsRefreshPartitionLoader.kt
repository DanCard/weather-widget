package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.shared.util.SettingsRefreshAllSources
import com.weatherwidget.shared.util.UserRefreshStampKey

/**
 * Android's inputs to [SettingsRefreshAllSources.partition] for Settings → Refresh data: the visible
 * sources, displayed first; last fetch = the later of the LONG-fetch marker and the last successful
 * check at this site (as [ViewingForecastRefresher]); last user refresh = [FetchMetadata]'s mark for the
 * source at this site ([userRefreshKey]).
 */
object SettingsRefreshPartitionLoader {
    /**
     * The [FetchMetadata] user-refresh key for [id] at [location]: per site, as the last fetch is.
     * A site-blind mark let Refresh data at one site skip every source at the next
     * (plans/261122-user-refresh-stamps-per-site.md).
     */
    fun userRefreshKey(id: String, location: Pair<Double, Double>?): String =
        UserRefreshStampKey.of(id, location?.first, location?.second)

    /**
     * [sourceId]'s last forecast fetch or successful check at [location]; null when none or no site.
     * Every forecast refresh throttle reads this ([com.weatherwidget.shared.util.UserRefreshThrottle]).
     */
    fun lastFetchMs(context: Context, stateManager: WidgetStateManager, sourceId: String, location: Pair<Double, Double>?): Long? =
        location?.let { (lat, lon) ->
            listOfNotNull(
                stateManager.getLastLongFetch(sourceId, lat, lon),
                FetchMetadata.getLastForecastSourceSuccessTime(context, sourceId, lat, lon).takeIf { it > 0L },
            ).maxOrNull()
        }

    fun load(
        context: Context,
        stateManager: WidgetStateManager,
        location: Pair<Double, Double>?,
        nowMs: Long,
    ): SettingsRefreshAllSources.Partition {
        val visible = stateManager.getVisibleSourcesOrder().filter { it != WeatherSource.GENERIC_GAP }.map { it.id }
        val order = SettingsRefreshAllSources.order(stateManager.getPrimarySource().id, visible)
        return SettingsRefreshAllSources.partition(
            order,
            lastFetchMs = { id -> lastFetchMs(context, stateManager, id, location) },
            lastUserRefreshMs = { id -> FetchMetadata.getLastUserRefreshTime(context, userRefreshKey(id, location)).takeIf { it > 0L } },
            nowMs = nowMs,
        )
    }
}
