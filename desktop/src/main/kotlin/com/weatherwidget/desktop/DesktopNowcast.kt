package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.nowcast.NowcastDisplay
import com.weatherwidget.shared.nowcast.NowcastStore
import com.weatherwidget.shared.nowcast.NowcastSummary
import com.weatherwidget.shared.util.DailyRainLabels
import java.io.File

/**
 * The desktop's one nowcast store, shared by the daemon (which fetches) and the popup process
 * (which only reads). A cache, beside the rain map's: losing it costs one request.
 */
object DesktopNowcast {
    val store: NowcastStore by lazy {
        val cacheHome = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.cache"
        NowcastStore(File(cacheHome, "weather-widget/nowcast"))
    }
}

/** The nowcast as the popup header, the hourly precip graph and the panel show it. */
internal object DesktopNowcastView {
    fun series(config: DesktopConfig, store: NowcastStore = DesktopNowcast.store, nowMs: Long = System.currentTimeMillis()) =
        NowcastDisplay.series(store, config.lat, config.lon, WeatherSource.fromDisplaySource(config.displaySource).id, nowMs)

    /** "14:15–" / "–15:30", or null. */
    fun headerText(config: DesktopConfig, store: NowcastStore = DesktopNowcast.store, nowMs: Long = System.currentTimeMillis()): String? =
        NowcastSummary.headerText(
            NowcastDisplay.summary(series(config, store, nowMs), nowMs),
            use24HourClock = DailyRainLabels.localeUses24HourClock(),
        )

    /** "Rain from 14:15", for tooltips. */
    fun description(config: DesktopConfig, store: NowcastStore = DesktopNowcast.store, nowMs: Long = System.currentTimeMillis()): String? =
        NowcastSummary.describe(
            NowcastDisplay.summary(series(config, store, nowMs), nowMs),
            use24HourClock = DailyRainLabels.localeUses24HourClock(),
        )
}
