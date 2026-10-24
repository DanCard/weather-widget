package com.weatherwidget.ui

import android.content.Context
import com.weatherwidget.R
import com.weatherwidget.shared.rainmap.RainLoopLoadError

/**
 * A rain loop's error line (WeatherNext or the Open-Meteo map): what failed, explicitly, naming the
 * host. Which line and its arguments are `:shared` [RainLoopLoadError.message]; this only maps the
 * line to its (translated) resource. Desktop prints [RainLoopLoadError.englishText].
 */
internal fun rainLoopErrorText(context: Context, error: RainLoopLoadError): String {
    val message = error.message()
    val resId = when (message.line) {
        RainLoopLoadError.Line.GONE -> R.string.sim_loop_error_gone
        RainLoopLoadError.Line.RATE_LIMITED -> R.string.sim_loop_error_rate_limited
        RainLoopLoadError.Line.HTTP -> R.string.sim_loop_error_http
        RainLoopLoadError.Line.TIMEOUT -> R.string.sim_loop_error_timeout
        RainLoopLoadError.Line.OFFLINE -> R.string.sim_loop_error_offline
        RainLoopLoadError.Line.BAD_DATA -> R.string.sim_loop_error_bad_data
        RainLoopLoadError.Line.NO_LAYER_FRAMES -> R.string.sim_loop_error_no_layer_frames
        RainLoopLoadError.Line.NO_FRAMES -> R.string.sim_loop_error_no_frames
        RainLoopLoadError.Line.OTHER -> R.string.sim_loop_error_other
    }
    return context.getString(resId, *message.args.toTypedArray())
}
