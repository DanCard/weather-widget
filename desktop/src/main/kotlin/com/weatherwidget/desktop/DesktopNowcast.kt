package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.nowcast.NowcastDisplay
import com.weatherwidget.shared.nowcast.NowcastStore
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

/** The nowcast as the hourly precip graph shows it. */
internal object DesktopNowcastView {
    fun series(config: DesktopConfig, store: NowcastStore = DesktopNowcast.store, nowMs: Long = System.currentTimeMillis()) =
        NowcastDisplay.series(store, config.lat, config.lon, WeatherSource.fromDisplaySource(config.displaySource).id, nowMs)
}
