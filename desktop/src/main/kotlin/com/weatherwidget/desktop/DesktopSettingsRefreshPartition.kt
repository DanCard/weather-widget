package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.SettingsRefreshAllSources
import com.weatherwidget.shared.util.UserRefreshStampKey

/**
 * Desktop's inputs to [SettingsRefreshAllSources.partition] for Settings → Refresh data: the usable
 * sources, displayed first; last fetch = the source's last forecast fetch AT [config]'s site, as on
 * Android (`SettingsRefreshPartitionLoader`). The site-blind lookup skipped Google as fetched 303 s
 * ago — at the previous location — while its data at the new one was 10 h old (2026-11-21,
 * plans/261121-desktop-location-change-freshness-and-config-clobber.md). The user-refresh time is
 * per site too: [lastUserRefreshMs] is looked up by [UserRefreshStampKey] at [config]'s site
 * (plans/261122-user-refresh-stamps-per-site.md).
 */
internal fun desktopSettingsRefreshPartition(
    dao: DesktopWeatherDao,
    config: DesktopConfig,
    lastUserRefreshMs: (stampKey: String) -> Long?,
    nowMs: Long,
): SettingsRefreshAllSources.Partition =
    SettingsRefreshAllSources.partition(
        SettingsRefreshAllSources.order(config.displaySource, config.effectiveSources),
        lastFetchMs = { dao.getLastSuccessfulFetch(it, config.lat, config.lon) },
        lastUserRefreshMs = { lastUserRefreshMs(UserRefreshStampKey.of(it, config.lat, config.lon)) },
        nowMs = nowMs,
    )
