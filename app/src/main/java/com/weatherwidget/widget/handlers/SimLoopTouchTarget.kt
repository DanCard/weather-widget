package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.NavArrowGeometry
import com.weatherwidget.shared.graph.SimLoopPill
import com.weatherwidget.ui.RainForecastActivity

/**
 * The "Sim loop" pill's tap target. The pill is drawn into the graph bitmap wherever
 * [SimLoopPill] finds empty space, so its RemoteViews touch zone is sized and moved to match on
 * every render — the first version was a fixed 80×40dp box that started where the pill ended, so a
 * tap on the pill landed on the source toggle or settings gear (Pixel 7 Pro, 2026-10-20).
 *
 * Moving a view needs `setViewLayoutMargin` (API 31+); below that the renderer draws no pill.
 * Coordinates are `widget_root`'s padded content area, which is what the zone's margins measure.
 */
internal object SimLoopTouchTarget {
    /** `widget_root`'s `android:padding`. */
    const val ROOT_PADDING_DP = 8f

    /**
     * Bottom of the header buttons in content dp: `top_right_header_container` sits at
     * `layout_marginTop="-10dp"` with 50dp-tall touch zones.
     */
    const val HEADER_TOUCH_BOTTOM_DP = 40f

    /** True when this launcher can move the zone onto the pill; the renderer skips the pill otherwise. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** graph_view's size in dp — `WidgetSizeCalculator.computeBitmapDimensions`' default paddings. */
    fun graphViewSizeDp(widgetWidthDp: Int, widgetHeightDp: Int): Pair<Float, Float> =
        (widgetWidthDp - 2 * ROOT_PADDING_DP - 2 * NavArrowGeometry.GRAPH_INSET_DP) to (widgetHeightDp - 2 * ROOT_PADDING_DP)

    /**
     * Maps a bitmap-pixel rect to content dp the way `graph_view` (`scaleType="fitCenter"`, inset
     * [NavArrowGeometry.GRAPH_INSET_DP] from the start) displays it: one uniform scale, centred.
     */
    fun bitmapToContentDp(
        rect: GraphRect,
        bitmapWidthPx: Int,
        bitmapHeightPx: Int,
        viewWidthDp: Float,
        viewHeightDp: Float,
    ): GraphRect {
        val k = minOf(viewWidthDp / bitmapWidthPx, viewHeightDp / bitmapHeightPx)
        val offX = NavArrowGeometry.GRAPH_INSET_DP + (viewWidthDp - bitmapWidthPx * k) / 2f
        val offY = (viewHeightDp - bitmapHeightPx * k) / 2f
        return GraphRect(offX + rect.left * k, offY + rect.top * k, offX + rect.right * k, offY + rect.bottom * k)
    }

    /**
     * The zone in content dp for a pill at [pillDp]: at least 48dp tall, inside the plot (header
     * buttons above, footer below) and off the nav arrows.
     */
    fun zoneDp(
        pillDp: GraphRect,
        plotBottomDp: Float,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): GraphRect {
        val arrowW = NavArrowGeometry.ARROW_WIDTH_DP
        val arrowTop = (contentHeightDp - NavArrowGeometry.ARROW_HEIGHT_DP) / 2f
        val arrowBottom = arrowTop + NavArrowGeometry.ARROW_HEIGHT_DP
        return SimLoopPill.touchBounds(
            pill = pillDp,
            density = 1f,
            container = GraphRect(0f, HEADER_TOUCH_BOTTOM_DP, contentWidthDp, plotBottomDp),
            avoid = listOf(
                GraphRect(0f, arrowTop, arrowW, arrowBottom),
                GraphRect(contentWidthDp - arrowW, arrowTop, contentWidthDp, arrowBottom),
            ),
        )
    }

    fun hide(views: RemoteViews) = views.setViewVisibility(R.id.sim_loop_touch_zone, View.GONE)

    /** Shows the zone over the drawn pill, or hides it when no pill was drawn. Returns the zone (content dp). */
    fun apply(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        pillPx: RectF?,
        plotBottomPx: Float,
        bitmapWidthPx: Int,
        bitmapHeightPx: Int,
        widgetWidthDp: Int,
        widgetHeightDp: Int,
        location: Pair<Double, Double>?,
    ): GraphRect? {
        if (pillPx == null || !supported()) {
            hide(views)
            return null
        }
        val (viewW, viewH) = graphViewSizeDp(widgetWidthDp, widgetHeightDp)
        fun map(r: GraphRect) = bitmapToContentDp(r, bitmapWidthPx, bitmapHeightPx, viewW, viewH)
        val pillDp = map(GraphRect(pillPx.left, pillPx.top, pillPx.right, pillPx.bottom))
        val plotBottomDp = map(GraphRect(0f, plotBottomPx, 0f, plotBottomPx)).bottom
        val zone = zoneDp(pillDp, plotBottomDp, widgetWidthDp - 2 * ROOT_PADDING_DP, widgetHeightDp - 2 * ROOT_PADDING_DP)

        views.setViewVisibility(R.id.sim_loop_touch_zone, View.VISIBLE)
        views.setViewLayoutWidth(R.id.sim_loop_touch_zone, zone.width, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.sim_loop_touch_zone, zone.height, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(R.id.sim_loop_touch_zone, RemoteViews.MARGIN_START, zone.left, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(R.id.sim_loop_touch_zone, RemoteViews.MARGIN_TOP, zone.top, TypedValue.COMPLEX_UNIT_DIP)

        val intent = Intent(context, RainForecastActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            location?.let { (lat, lon) ->
                putExtra(RainForecastActivity.EXTRA_LAT, lat)
                putExtra(RainForecastActivity.EXTRA_LON, lon)
            }
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val pending = PendingIntent.getActivity(
            context,
            WidgetRequestCodes.simLoop(appWidgetId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.sim_loop_touch_zone, pending)
        return zone
    }
}
