package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.weatherwidget.R

internal object HeaderRemoteViewsBinder {
    private const val TAG = "HeaderRemoteViewsBinder"
    private val HEADER_TEXT_COLOR = 0xAAFFFFFF.toInt()

    fun bindCurrentTemp(
        context: Context,
        views: RemoteViews,
        formattedTemp: String?,
        textSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
        scale: Float = 1.0f,
        hideDeltaOnNull: Boolean = false,
    ) {
        if (formattedTemp != null) {
            views.setTextViewText(R.id.current_temp, formattedTemp)
            val tempPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSizeDp * scale,
                context.resources.displayMetrics,
            )
            views.setTextViewTextSize(R.id.current_temp, TypedValue.COMPLEX_UNIT_PX, tempPx)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)
            views.setViewVisibility(R.id.current_temp_zone, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
            views.setViewVisibility(R.id.current_temp_zone, View.GONE)
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
        scale: Float = 1.0f,
    ) {
        if (precipText != null) {
            views.setTextViewText(R.id.precip_probability, precipText)
            val precipPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSizeDp * scale,
                context.resources.displayMetrics,
            )
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_PX, precipPx)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
            views.setViewVisibility(R.id.precip_touch_zone, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
            views.setViewVisibility(R.id.precip_touch_zone, View.GONE)
        }
    }

    fun bindDelta(
        context: Context,
        views: RemoteViews,
        deltaText: String?,
        deltaVisible: Boolean,
        scale: Float = 1.0f,
    ) {
        if (deltaVisible && deltaText != null) {
            val deltaColor = Color.parseColor("#FF6B35")
            views.setTextViewText(R.id.current_temp_delta, deltaText)
            views.setTextColor(R.id.current_temp_delta, deltaColor)
            val deltaPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                HeaderConstants.DELTA_TEXT_SIZE_DP * scale,
                context.resources.displayMetrics,
            )
            views.setTextViewTextSize(R.id.current_temp_delta, TypedValue.COMPLEX_UNIT_PX, deltaPx)
            views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }
    }

    fun bindApiSource(
        context: Context,
        views: RemoteViews,
        sourceText: String?,
        textSizeDp: Float,
        scale: Float = 1.0f,
    ) {
        if (sourceText != null) {
            views.setTextViewText(R.id.api_source, sourceText)
            val apiPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSizeDp * scale,
                context.resources.displayMetrics,
            )
            views.setTextViewTextSize(R.id.api_source, TypedValue.COMPLEX_UNIT_PX, apiPx)
            views.setViewVisibility(R.id.api_source, View.VISIBLE)
            views.setViewVisibility(R.id.api_touch_zone, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.api_source, View.GONE)
            views.setViewVisibility(R.id.api_touch_zone, View.GONE)
        }
    }

    fun bindScaledIcon(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        iconRes: Int,
        sizeDp: Float,
        scale: Float = 1.0f,
        tintColor: Int? = null,
    ) {
        if (iconRes == 0) {
            views.setViewVisibility(viewId, View.GONE)
            return
        }

        // Use Bitmap logic if we need to scale OR if the target size differs from standard 24dp intrinsic size.
        // This is necessary because we changed the XML to wrap_content to allow scaling, but 
        // vector drawables will default to their 24dp intrinsic size otherwise.
        val isStandardSize = Math.abs(sizeDp - 24f) < 0.1f
        if (scale <= 1.0f && isStandardSize) {
            views.setImageViewResource(viewId, iconRes)
            if (tintColor != null) {
                views.setInt(viewId, "setColorFilter", tintColor)
            } else {
                views.setInt(viewId, "setColorFilter", 0)
            }
            views.setViewVisibility(viewId, View.VISIBLE)
            return
        }

        try {
            val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
            if (drawable != null) {
                if (tintColor != null) {
                    drawable.setTint(tintColor)
                }
                val baseSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    sizeDp,
                    context.resources.displayMetrics,
                )
                val scaledSizePx = (baseSizePx * scale).toInt()
                if (scaledSizePx <= 0) {
                    views.setViewVisibility(viewId, View.GONE)
                    return
                }
                val bitmap = Bitmap.createBitmap(scaledSizePx, scaledSizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, scaledSizePx, scaledSizePx)
                drawable.draw(canvas)
                views.setImageViewBitmap(viewId, bitmap)
                views.setViewVisibility(viewId, View.VISIBLE)
            } else {
                views.setViewVisibility(viewId, View.GONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "bindScaledIcon: failed to scale icon $iconRes", e)
            views.setImageViewResource(viewId, iconRes)
            views.setViewVisibility(viewId, View.VISIBLE)
        }
    }

    fun hideIconWidthControls(views: RemoteViews) {
        views.setViewVisibility(R.id.top_right_header_container, View.GONE)
        views.setViewVisibility(R.id.api_source, View.GONE)
        views.setViewVisibility(R.id.api_touch_zone, View.GONE)
        views.setViewVisibility(R.id.settings_icon, View.GONE)
        views.setViewVisibility(R.id.settings_touch_zone, View.GONE)
        views.setViewVisibility(R.id.dual_touch_zone, View.GONE)
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
        val precipVis = if (isPrecipVisible && disclosure.showsPrecip()) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.precip_probability, precipVis)
        views.setViewVisibility(R.id.precip_touch_zone, precipVis)
        if (disclosure == HeaderDisclosureLevel.NONE) {
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }
    }
}

