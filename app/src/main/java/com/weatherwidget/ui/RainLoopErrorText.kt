package com.weatherwidget.ui

import android.content.Context
import com.weatherwidget.R
import com.weatherwidget.shared.rainmap.RainLoopLoadError

/**
 * A rain loop's error line (WeatherNext or the Open-Meteo map): what failed, explicitly, naming the
 * host. Desktop's twin is `rainLoopErrorText` in `RainForecastWindow.kt`.
 */
internal fun rainLoopErrorText(context: Context, error: RainLoopLoadError): String {
    val host = error.host
    return when (error.kind) {
        RainLoopLoadError.Kind.GONE -> context.getString(R.string.sim_loop_error_gone, host, error.status ?: 404)
        RainLoopLoadError.Kind.RATE_LIMITED -> context.getString(R.string.sim_loop_error_rate_limited, host)
        RainLoopLoadError.Kind.HTTP -> context.getString(R.string.sim_loop_error_http, host, error.status ?: 0)
        RainLoopLoadError.Kind.TIMEOUT -> context.getString(R.string.sim_loop_error_timeout, host)
        RainLoopLoadError.Kind.OFFLINE -> context.getString(R.string.sim_loop_error_offline, host)
        RainLoopLoadError.Kind.BAD_DATA -> context.getString(R.string.sim_loop_error_bad_data, host)
        RainLoopLoadError.Kind.NO_FRAMES ->
            if (error.frameCount == 0) context.getString(R.string.sim_loop_error_no_layer_frames)
            else context.getString(R.string.sim_loop_error_no_frames, error.frameCount, host)
        RainLoopLoadError.Kind.OTHER -> context.getString(R.string.sim_loop_error_other, host)
    }
}
