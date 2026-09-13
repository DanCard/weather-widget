package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.ui.BackgroundDataResolutionActivity
import com.weatherwidget.ui.SettingsActivity

internal object ErrorPillTouchTargetHelper {

    fun setupErrorPillTouchTarget(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        showErrorWatermark: Boolean,
        errorCode: String? = null,
    ) {
        if (!showErrorWatermark) {
            views.setViewVisibility(R.id.error_pill_touch_zone, View.GONE)
            return
        }

        views.setViewVisibility(R.id.error_pill_touch_zone, View.VISIBLE)

        val targetIntent = if (errorCode == "HTTP_401" || errorCode == "HTTP_403" || errorCode == "ACCESS_ERROR") {
            Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(context, BackgroundDataResolutionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            WidgetRequestCodes.errorPill(appWidgetId),
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        views.setOnClickPendingIntent(R.id.error_pill_touch_zone, pendingIntent)
    }
}
