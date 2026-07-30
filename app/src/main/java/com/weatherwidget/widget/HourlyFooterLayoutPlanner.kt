package com.weatherwidget.widget

import com.weatherwidget.shared.graph.GraphRect

/** Pure placement planner for hourly footer labels and optional inline weather icons. */
internal object HourlyFooterLayoutPlanner {
    data class LabelInput(
        val itemIndex: Int,
        val centerX: Float,
        val text: String,
        val showLabel: Boolean,
        val hasIcon: Boolean,
        val isDateLabel: Boolean,
    )

    data class LabelPlacement(
        val itemIndex: Int,
        val text: String,
        val textCenterX: Float,
        val baselineY: Float,
        val textBounds: GraphRect,
        val iconBounds: GraphRect?,
    )

    data class Plan(
        val spacingPx: Float,
        val drawsIcons: Boolean,
        val usedFallback: Boolean,
        val placements: List<LabelPlacement>,
    )

    private data class LayoutConfig(
        val spacingPx: Float,
        val drawsIcons: Boolean,
    )

    private sealed interface Attempt {
        data class Fits(val placements: List<LabelPlacement>) : Attempt
        data object Overlaps : Attempt
        data object IconGroupTooWide : Attempt
    }

    fun plan(
        items: List<LabelInput>,
        widthPx: Int,
        heightPx: Int,
        minSpacingPx: Float,
        textAscent: Float,
        textDescent: Float,
        measureText: (String) -> Float,
        iconSizePx: Float,
        iconTextGapPx: Float,
        footerBottomInsetPx: Float,
        minLabelGapPx: Float,
        dateLabelGapPx: Float,
        iconsAvailable: Boolean,
    ): Plan {
        if (widthPx <= 0 || heightPx <= 0 || items.isEmpty()) {
            return Plan(minSpacingPx, drawsIcons = false, usedFallback = false, placements = emptyList())
        }

        val configs = listOf(
            LayoutConfig(minSpacingPx, drawsIcons = true),
            LayoutConfig(minSpacingPx, drawsIcons = false),
            LayoutConfig(minSpacingPx * 1.4f, drawsIcons = true),
            LayoutConfig(minSpacingPx * 1.4f, drawsIcons = false),
            LayoutConfig(minSpacingPx * 1.8f, drawsIcons = true),
            LayoutConfig(minSpacingPx * 1.8f, drawsIcons = false),
            LayoutConfig(minSpacingPx * 2.2f, drawsIcons = false),
        )

        for (config in configs) {
            val effectiveConfig = config.copy(drawsIcons = config.drawsIcons && iconsAvailable)
            when (
                val attempt = attemptLayout(
                    items = items,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    config = effectiveConfig,
                    textAscent = textAscent,
                    textDescent = textDescent,
                    measureText = measureText,
                    iconSizePx = iconSizePx,
                    iconTextGapPx = iconTextGapPx,
                    footerBottomInsetPx = footerBottomInsetPx,
                    minLabelGapPx = minLabelGapPx,
                    dateLabelGapPx = dateLabelGapPx,
                    dropOrdinaryOverlaps = false,
                )
            ) {
                is Attempt.Fits ->
                    return Plan(
                        spacingPx = effectiveConfig.spacingPx,
                        drawsIcons = effectiveConfig.drawsIcons,
                        usedFallback = false,
                        placements = attempt.placements,
                    )

                Attempt.IconGroupTooWide,
                Attempt.Overlaps,
                -> Unit
            }
        }

        val safest = configs.last()
        val fallback = attemptLayout(
            items = items,
            widthPx = widthPx,
            heightPx = heightPx,
            config = safest,
            textAscent = textAscent,
            textDescent = textDescent,
            measureText = measureText,
            iconSizePx = iconSizePx,
            iconTextGapPx = iconTextGapPx,
            footerBottomInsetPx = footerBottomInsetPx,
            minLabelGapPx = minLabelGapPx,
            dateLabelGapPx = dateLabelGapPx,
            dropOrdinaryOverlaps = true,
        )
        return Plan(
            spacingPx = safest.spacingPx,
            drawsIcons = false,
            usedFallback = true,
            placements = (fallback as? Attempt.Fits)?.placements.orEmpty(),
        )
    }

    fun placeDateLabelCenter(
        centerX: Float,
        leftExtent: Float,
        rightExtent: Float,
        widthPx: Int,
        previousRightPx: Float,
        minGapPx: Float,
    ): Float? {
        if (widthPx <= 0 || leftExtent + rightExtent > widthPx) return null
        val clamped = centerX.coerceIn(leftExtent, widthPx - rightExtent)
        if (clamped - leftExtent < previousRightPx + minGapPx) return null
        return clamped
    }

    private fun attemptLayout(
        items: List<LabelInput>,
        widthPx: Int,
        heightPx: Int,
        config: LayoutConfig,
        textAscent: Float,
        textDescent: Float,
        measureText: (String) -> Float,
        iconSizePx: Float,
        iconTextGapPx: Float,
        footerBottomInsetPx: Float,
        minLabelGapPx: Float,
        dateLabelGapPx: Float,
        dropOrdinaryOverlaps: Boolean,
    ): Attempt {
        val lastSelectedIndex = selectBySpacing(items, config.spacingPx).lastOrNull()?.itemIndex
        val iconBottom = heightPx - footerBottomInsetPx
        val iconTop = iconBottom - iconSizePx
        val baselineY =
            if (config.drawsIcons && iconSizePx > 0f) {
                (iconTop + iconBottom) / 2f - (textAscent + textDescent) / 2f
            } else {
                heightPx - footerBottomInsetPx
            }

        var previousRight = Float.NEGATIVE_INFINITY
        var previousDateRight = Float.NEGATIVE_INFINITY
        var previousPlacedCenterX = Float.NEGATIVE_INFINITY
        val placements = mutableListOf<LabelPlacement>()

        items.forEach { item ->
            if (
                !item.showLabel ||
                (
                    previousPlacedCenterX.isFinite() &&
                        item.centerX - previousPlacedCenterX < config.spacingPx
                )
            ) {
                return@forEach
            }
            val textWidth = measureText(item.text)
            val inline =
                config.drawsIcons &&
                    iconSizePx > 0f &&
                    item.hasIcon &&
                    (item.itemIndex != lastSelectedIndex || item.isDateLabel)
            val leftExtent = textWidth / 2f
            val rightExtent =
                textWidth / 2f + if (inline) iconTextGapPx + iconSizePx else 0f

            if (leftExtent + rightExtent > widthPx) {
                if (inline) return Attempt.IconGroupTooWide
                return@forEach
            }

            val center =
                if (item.isDateLabel) {
                    placeDateLabelCenter(
                        centerX = item.centerX,
                        leftExtent = leftExtent,
                        rightExtent = rightExtent,
                        widthPx = widthPx,
                        previousRightPx = previousDateRight,
                        minGapPx = dateLabelGapPx,
                    ) ?: return@forEach
                } else {
                    item.centerX.coerceIn(leftExtent, widthPx - rightExtent)
                }

            val groupLeft = center - leftExtent
            val groupRight = center + rightExtent
            if (previousRight.isFinite() && groupLeft < previousRight + minLabelGapPx) {
                if (dropOrdinaryOverlaps) return@forEach
                return Attempt.Overlaps
            }

            val iconBounds =
                if (inline) {
                    val iconLeft = center + textWidth / 2f + iconTextGapPx
                    GraphRect(iconLeft, iconTop, iconLeft + iconSizePx, iconBottom)
                } else {
                    null
                }
            placements += LabelPlacement(
                itemIndex = item.itemIndex,
                text = item.text,
                textCenterX = center,
                baselineY = baselineY,
                textBounds = GraphRect(
                    center - textWidth / 2f,
                    baselineY + textAscent,
                    center + textWidth / 2f,
                    baselineY + textDescent,
                ),
                iconBounds = iconBounds,
            )
            previousRight = groupRight
            previousPlacedCenterX = item.centerX
            if (item.isDateLabel) previousDateRight = groupRight
        }
        return Attempt.Fits(placements)
    }

    private fun selectBySpacing(
        items: List<LabelInput>,
        spacingPx: Float,
    ): List<LabelInput> {
        var previousCenterX = Float.NEGATIVE_INFINITY
        return items.filter { item ->
            val selected =
                item.showLabel &&
                    (!previousCenterX.isFinite() || item.centerX - previousCenterX >= spacingPx)
            if (selected) previousCenterX = item.centerX
            selected
        }
    }
}
