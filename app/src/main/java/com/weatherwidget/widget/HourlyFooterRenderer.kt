package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.core.content.ContextCompat
import com.weatherwidget.shared.graph.HourlyGraphDefaults

/** Paint/Canvas adapter for the pure [HourlyFooterLayoutPlanner]. */
internal object HourlyFooterRenderer {
    private const val TAG = "HourlyFooterRenderer"
    private const val MIN_LABEL_GAP_DP = 3f

    fun isNarrowWidget(numColumns: Int): Boolean =
        numColumns <= HourlyGraphDefaults.NARROW_WIDGET_MAX_COLUMNS

    fun iconGapDp(numColumns: Int): Float =
        if (isNarrowWidget(numColumns)) HourlyGraphDefaults.FOOTER_ICON_GAP_NARROW_DP
        else HourlyGraphDefaults.FOOTER_ICON_GAP_WIDE_DP

    fun iconSize(hourLabelTextPaint: Paint): Float {
        val fontMetrics = hourLabelTextPaint.fontMetrics
        val metricHeight =
            if (fontMetrics != null) fontMetrics.descent - fontMetrics.ascent else 0f
        val basis = if (metricHeight > 0f) metricHeight else hourLabelTextPaint.textSize
        return basis * HourlyGraphDefaults.FOOTER_ICON_TO_TEXT_RATIO
    }

    fun <T> planHourLabels(
        items: List<T>,
        points: List<Pair<Float, Float>>,
        widthPx: Int,
        heightPx: Int,
        minHourLabelSpacing: Float,
        hourLabelTextPaint: Paint,
        dpToPx: (Float) -> Float,
        showLabel: (T) -> Boolean,
        labelText: (T) -> String,
        iconSize: Float = 0f,
        iconTextGapDp: Float = 0f,
        hasIcon: (T) -> Boolean = { false },
        isDateLabel: (T) -> Boolean = { false },
        iconsAvailable: Boolean,
    ): HourlyFooterLayoutPlanner.Plan {
        val fontMetrics = hourLabelTextPaint.fontMetrics
        val textAscent =
            fontMetrics?.ascent?.takeIf { it != 0f } ?: -hourLabelTextPaint.textSize
        val textDescent =
            fontMetrics?.descent?.takeIf { it != 0f } ?: hourLabelTextPaint.textSize * 0.2f
        val inputs =
            items.mapIndexedNotNull { index, item ->
                points.getOrNull(index)?.let { point ->
                    HourlyFooterLayoutPlanner.LabelInput(
                        itemIndex = index,
                        centerX = point.first,
                        text = labelText(item),
                        showLabel = showLabel(item),
                        hasIcon = hasIcon(item),
                        isDateLabel = isDateLabel(item),
                    )
                }
            }
        return HourlyFooterLayoutPlanner.plan(
            items = inputs,
            widthPx = widthPx,
            heightPx = heightPx,
            minSpacingPx = minHourLabelSpacing,
            textAscent = textAscent,
            textDescent = textDescent,
            measureText = hourLabelTextPaint::measureText,
            iconSizePx = iconSize,
            iconTextGapPx = dpToPx(iconTextGapDp),
            footerBottomInsetPx = dpToPx(HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP),
            minLabelGapPx = dpToPx(MIN_LABEL_GAP_DP),
            dateLabelGapPx = dpToPx(HourlyGraphDefaults.DATE_LABEL_MIN_GAP_DP),
            iconsAvailable = iconsAvailable && inputs.any { it.hasIcon },
        )
    }

    fun drawPlan(
        canvas: Canvas,
        plan: HourlyFooterLayoutPlanner.Plan,
        hourLabelTextPaint: Paint,
        drawIcon: ((index: Int, iconRect: RectF) -> Unit)? = null,
    ) {
        plan.placements.forEach { placement ->
            canvas.drawText(
                placement.text,
                placement.textCenterX,
                placement.baselineY,
                hourLabelTextPaint,
            )
            placement.iconBounds?.let { bounds ->
                drawIcon?.invoke(
                    placement.itemIndex,
                    RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
                )
            }
        }
        Log.v(
            TAG,
            "drawHourLabels: spacing=${plan.spacingPx} drawIcons=${plan.drawsIcons} " +
                "fallback=${plan.usedFallback} drawn=${plan.placements.size}",
        )
    }

    fun drawHourIcon(
        context: Context,
        canvas: Canvas,
        iconRes: Int,
        iconRect: RectF,
        isRainy: Boolean,
        isMixed: Boolean,
        isNight: Boolean,
        isTwilight: Boolean,
        isSunny: Boolean,
    ) {
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return
        drawable.setBounds(
            iconRect.left.toInt(),
            iconRect.top.toInt(),
            iconRect.right.toInt(),
            iconRect.bottom.toInt(),
        )
        if (!isRainy && !isMixed) {
            drawable.setTint(
                when {
                    isNight -> HourlyGraphDefaults.ICON_TINT_NIGHT
                    isTwilight -> HourlyGraphDefaults.ICON_TINT_TWILIGHT
                    isSunny -> HourlyGraphDefaults.ICON_TINT_SUNNY
                    else -> HourlyGraphDefaults.ICON_TINT_DEFAULT
                },
            )
        }
        drawable.draw(canvas)
    }
}
