package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.shared.util.FailureBannerStage
import com.weatherwidget.widget.GraphFailureWatermarkRenderer
import com.weatherwidget.ui.SourceErrorDetailsActivity

internal object ErrorPillTouchTargetHelper {
    private const val FULL_TOUCH_HEIGHT_DP = 50f
    private const val TINY_TOUCH_HEIGHT_DP = 22f

    fun setupErrorPillTouchTarget(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        showErrorWatermark: Boolean,
        errorCode: String? = null,
        bannerSinceMs: Long? = null,
        sourceId: String? = null,
    ) {
        if (!showErrorWatermark) {
            views.setViewVisibility(R.id.error_pill_touch_zone, View.GONE)
            return
        }
        FailureBannerRepaint.scheduleNextStage(context, appWidgetId, bannerSinceMs)

        views.setViewVisibility(R.id.error_pill_touch_zone, View.VISIBLE)
        // The tiny pill is a third of the full one's height; a full-height target under it would
        // swallow taps on the graph. Pre-31 launchers keep the layout's full height.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val stage = GraphFailureWatermarkRenderer.stageFor(bannerSinceMs, System.currentTimeMillis())
            val heightDp = if (stage == FailureBannerStage.FULL) FULL_TOUCH_HEIGHT_DP else TINY_TOUCH_HEIGHT_DP
            views.setViewLayoutHeight(R.id.error_pill_touch_zone, heightDp, TypedValue.COMPLEX_UNIT_DIP)
        }

        // Every failure opens its details page; the page offers Settings or the background-data
        // screen when that is the fix (it used to jump straight there, for a 429 too).
        val targetIntent = SourceErrorDetailsActivity.intent(context, sourceId)

        val pendingIntent = PendingIntent.getActivity(
            context,
            WidgetRequestCodes.errorPill(appWidgetId),
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        views.setOnClickPendingIntent(R.id.error_pill_touch_zone, pendingIntent)
    }
}
