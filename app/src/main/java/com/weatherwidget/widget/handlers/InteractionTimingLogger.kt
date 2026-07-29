package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log

internal object InteractionTimingLogger {
    private const val SLOW_THRESHOLD_MS = 200L

    suspend fun log(
        database: WeatherDatabase,
        appWidgetId: Int,
        actionTag: String,
        startTimeMs: Long,
        extraMetadata: String,
    ) {
        val totalMs = SystemClock.elapsedRealtime() - startTimeMs
        val metadataString = extraMetadata.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
        database.appLogDao().log(
            "${actionTag}_TIMING",
            "widget=$appWidgetId total=${totalMs}ms$metadataString",
        )
        if (totalMs > SLOW_THRESHOLD_MS) {
            database.appLogDao().log(
                "${actionTag}_SLOW",
                "widget=$appWidgetId total=${totalMs}ms$metadataString",
            )
        }
    }
}
