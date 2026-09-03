package com.weatherwidget.desktop

/**
 * Re-export of the shared [com.weatherwidget.widget.ViewMode] enum. Desktop previously declared its
 * own ViewMode with a legacy `HOURLY` member; that member is now the shared `TEMPERATURE`, and
 * legacy `"HOURLY"` config.json values are migrated on load (see
 * [DesktopConfigStore.migrateLegacyHourlyViewMode]).
 */
typealias ViewMode = com.weatherwidget.widget.ViewMode
