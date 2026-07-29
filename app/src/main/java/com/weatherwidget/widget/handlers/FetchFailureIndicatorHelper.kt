package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object FetchFailureIndicatorHelper {
    suspend fun resolveFetchError(
        displaySourceId: String,
        appLogDao: AppLogDao,
        lastGoodObsMs: Long? = null,
    ): String? {
        val status = appLogDao.getLatestCurrentTempStatus(displaySourceId)
        if (status == null || status.message.contains("ok=true")) {
            return null
        }

        val msg = status.message
        val className = msg.substringAfter("class=").substringBefore(" detail=")
        val detail = msg.substringAfter("detail=")
        
        val host = when {
            detail.contains("open-meteo.com") -> "api.open-meteo.com"
            detail.contains("weather.gov") -> "api.weather.gov"
            detail.contains("tomorrow.io") -> "api.tomorrow.io"
            detail.contains("weatherapi.com") -> "api.weatherapi.com"
            detail.contains("visualcrossing.com") -> "weather.visualcrossing.com"
            detail.contains("openweathermap.org") -> "api.openweathermap.org"
            detail.contains("silurian") -> "silurian API"
            else -> ""
        }
        
        val friendlyError = when (className) {
            "ConnectTimeoutException" -> "Connect timeout (10s)"
            "SocketTimeoutException" -> "Socket timeout"
            "UnknownHostException" -> "Unknown host (DNS lookup failed)"
            else -> detail.substringBefore(" [").take(40)
        }
        val errorLine = if (host.isNotEmpty()) "$friendlyError · $host" else friendlyError

        val timeFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
        val attemptFmt = DateTimeFormatter.ofPattern("H:mm:ss").withZone(ZoneId.systemDefault())
        
        val lastGoodLine = if (lastGoodObsMs != null && lastGoodObsMs > 0L) {
            val timeStr = timeFmt.format(Instant.ofEpochMilli(lastGoodObsMs))
            val ageMs = System.currentTimeMillis() - lastGoodObsMs
            val ageMin = ageMs / 60_000L
            val ageStr = if (ageMin < 60) "${ageMin}m" else "${ageMin / 60}h ${ageMin % 60}m"
            "Last good obs: $timeStr ($ageStr ago)"
        } else {
            "Last good obs: None"
        }
        
        val attemptTimeStr = attemptFmt.format(Instant.ofEpochMilli(status.timestamp))
        val attemptLine = "Last attempt: $attemptTimeStr · 2 retries failed"
        
        val displayName = displaySourceId.uppercase().replace("-", "_")
        return """
            $displayName current temp not updating
            $errorLine
            $lastGoodLine
            $attemptLine
        """.trimIndent()
    }

    fun bind(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        errorMessage: String?,
    ) {
        if (errorMessage != null) {
            views.setViewVisibility(R.id.current_temp_warning, View.VISIBLE)
            
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_SHOW_TOAST
                putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, errorMessage)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.apiToggle(appWidgetId) + 8000,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.current_temp_warning, pendingIntent)
        } else {
            views.setViewVisibility(R.id.current_temp_warning, View.GONE)
            views.setOnClickPendingIntent(R.id.current_temp_warning, null)
        }
    }
}
