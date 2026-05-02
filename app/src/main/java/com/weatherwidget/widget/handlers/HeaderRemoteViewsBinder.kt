package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R

internal object HeaderRemoteViewsBinder {

    fun bindCurrentTemp(
        context: Context,
        views: RemoteViews,
        formattedTemp: String?,
        textSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
        hideDeltaOnNull: Boolean = false,
    ) {
        if (formattedTemp != null) {
            views.setTextViewText(R.id.current_temp, formattedTemp)
            val tempPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSizeDp,
                context.resources.displayMetrics,
            )
            views.setTextViewTextSize(R.id.current_temp, TypedValue.COMPLEX_UNIT_PX, tempPx)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
            if (hideDeltaOnNull) {
                views.setViewVisibility(R.id.current_temp_delta, View.GONE)
            }
        }
    }

    fun bindPrecipProbability(
        context: Context,
        views: RemoteViews,
        precipText: String?,
        textSizeDp: Float,
    ) {
        if (precipText != null) {
            views.setTextViewText(R.id.precip_probability, precipText)
            val precipPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSizeDp,
                context.resources.displayMetrics,
            )
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_PX, precipPx)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }
    }

    fun bindDelta(
        context: Context,
        views: RemoteViews,
        deltaText: String?,
        deltaVisible: Boolean,
    ) {
        if (deltaVisible && deltaText != null) {
            val deltaColor = Color.parseColor("#FF6B35")
            views.setTextViewText(R.id.current_temp_delta, deltaText)
            views.setTextColor(R.id.current_temp_delta, deltaColor)
            val deltaPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                HeaderConstants.DELTA_TEXT_SIZE_DP,
                context.resources.displayMetrics,
            )
            views.setTextViewTextSize(R.id.current_temp_delta, TypedValue.COMPLEX_UNIT_PX, deltaPx)
            views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }
    }

    fun hideIconWidthControls(views: RemoteViews) {
        views.setViewVisibility(R.id.api_source_container, View.GONE)
        views.setViewVisibility(R.id.api_source, View.GONE)
        views.setViewVisibility(R.id.api_touch_zone, View.GONE)
        views.setViewVisibility(R.id.settings_icon, View.GONE)
        views.setViewVisibility(R.id.settings_touch_zone, View.GONE)
        views.setViewVisibility(R.id.text_mode_api_source_container, View.GONE)
        views.setViewVisibility(R.id.text_mode_api_source, View.GONE)
        views.setViewVisibility(R.id.text_mode_api_touch_zone, View.GONE)
        views.setViewVisibility(R.id.text_mode_settings_icon, View.GONE)
        views.setViewVisibility(R.id.text_mode_settings_touch_zone, View.GONE)
    }

    fun applyDisclosure(
        views: RemoteViews,
        disclosure: HeaderDisclosureLevel,
        isDeltaVisible: Boolean = false,
        isPrecipVisible: Boolean = false,
    ) {
        views.setViewVisibility(R.id.weather_icon, if (disclosure.showsIcon()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.current_temp_delta, if (isDeltaVisible && disclosure.showsDelta()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.precip_probability, if (isPrecipVisible && disclosure.showsPrecip()) View.VISIBLE else View.GONE)
        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible && disclosure.showsPrecip())
        if (disclosure == HeaderDisclosureLevel.NONE) {
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }
    }
}
