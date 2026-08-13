package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.weatherwidget.shared.graph.GraphLabelPlacementUtils
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourData
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.max

internal object TemperatureFetchDotRenderer {
    private const val STALENESS_MINOR_OVERLAP_RATIO = 0.40f
    private const val MAX_STALENESS_DISPLACEMENT_STEPS = 15
    private const val STALENESS_LEADER_LINE_MIN_STEPS = 2
    private const val VALUE_LABEL_BASELINE_DIVISOR = 3f

    data class Input(
        val context: Context,
        val canvas: Canvas,
        val widthPx: Int,
        val heightPx: Int,
        val labelScale: Float,
        val graphTop: Float,
        val graphHeight: Float,
        val minTemp: Float,
        val tempRange: Float,
        val fetchTime: LocalDateTime?,
        val fetchDotX: Float?,
        val lastObservedTemp: Float?,
        val observedAt: Long?,
        val currentTime: LocalDateTime,
        val hours: List<HourData>,
        val paints: PaintSet,
        val useCelsius: Boolean,
        val onResolved: ((FetchDotDebug) -> Unit)?,
    ) {
        fun tempToY(temp: Float): Float =
            TemperatureGraphStyle.tempToY(
                temp = temp,
                graphTop = graphTop,
                graphHeight = graphHeight,
                minTemp = minTemp,
                tempRange = tempRange,
            )
    }

    data class Plan(
        internal val observedAt: Long,
        internal val clampedX: Float,
        internal val fetchY: Float,
        internal val dotRadius: Float,
        internal val outerRadius: Float,
        internal val lastObservedTemp: Float,
        internal val valueLabel: String,
        internal val valueLayout: ValueLabelLayout?,
        internal val staleness: StalenessPlan?,
    ) {
        val reservationBounds: List<RectF>
            get() =
                buildList {
                    add(ringBounds())
                    valueLayout?.let { add(RectF(it.bounds)) }
                    staleness?.let { add(RectF(it.provisional.bounds)) }
                }

        internal fun ringBounds(): RectF =
            RectF(
                clampedX - outerRadius,
                fetchY - outerRadius,
                clampedX + outerRadius,
                fetchY + outerRadius,
            )
    }

    internal data class StalenessPlan(
        val ageLabel: String,
        val ageWidth: Float,
        val ascent: Float,
        val descent: Float,
        val padding: Float,
        val minorOverlapThreshold: Float,
        val provisional: StalenessInitialLayout,
    )

    fun plan(input: Input): Plan? {
        val observedAt = input.observedAt ?: return null
        val fetchDotX = input.fetchDotX ?: return null
        if (fetchDotX < 0f || fetchDotX > input.widthPx) return null
        val lastObservedTemp = input.lastObservedTemp ?: return null

        val fetchY = input.tempToY(lastObservedTemp)
        val dotRadius =
            TemperatureGraphStyle.dpToPx(
                input.context,
                TemperatureGraphStyle.DOT_RADIUS_DP * input.labelScale,
            )
        val clampedX = fetchDotX.coerceIn(dotRadius, input.widthPx - dotRadius)
        val outerRadius = dotRadius + input.paints.ringPaint.strokeWidth / 2f
        val valueLabel = TemperatureGraphStyle.formatTemp(lastObservedTemp, input.useCelsius) + "°"
        val valuePaint = input.paints.fetchDotValueTextPaint
        val ageLabel = resolveAgeLabel(input)
        val baseSideGap =
            TemperatureGraphStyle.dpToPx(
                input.context,
                TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * input.labelScale,
            )
        val ageValueSeparation =
            ageLabel
                ?.let(input.paints.stalenessTextPaint::measureText)
                ?.let { ageWidth ->
                    ageWidth / 2f -
                        dotRadius +
                        TemperatureGraphStyle.dpToPx(input.context, input.labelScale)
                }
                ?: 0f
        val valueLayout =
            resolveValueLabelLayout(
                clampedX = clampedX,
                fetchY = fetchY,
                dotRadius = dotRadius,
                valueWidth = valuePaint.measureText(valueLabel),
                sideGap = max(baseSideGap, ageValueSeparation),
                aboveGap =
                    TemperatureGraphStyle.dpToPx(
                        input.context,
                        TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * input.labelScale,
                    ),
                widthPx = input.widthPx,
                baselineOffset = valuePaint.textSize / VALUE_LABEL_BASELINE_DIVISOR,
                ascent = TemperatureGraphStyle.fontAscent(valuePaint),
                descent = TemperatureGraphStyle.fontDescent(valuePaint),
            )

        val staleness =
            resolveStalenessPlan(
                input = input,
                clampedX = clampedX,
                fetchY = fetchY,
                dotRadius = dotRadius,
                ringBounds =
                    RectF(
                        clampedX - outerRadius,
                        fetchY - outerRadius,
                        clampedX + outerRadius,
                        fetchY + outerRadius,
                ),
                valueLayout = valueLayout,
                ageLabel = ageLabel,
            )

        return Plan(
            observedAt = observedAt,
            clampedX = clampedX,
            fetchY = fetchY,
            dotRadius = dotRadius,
            outerRadius = outerRadius,
            lastObservedTemp = lastObservedTemp,
            valueLabel = valueLabel,
            valueLayout = valueLayout,
            staleness = staleness,
        )
    }

    fun reserve(
        plan: Plan,
        obstacles: TemperatureGraphObstacleRegistry,
    ) {
        obstacles.add(TemperatureGraphObstacleType.FETCH_DOT_RING, plan.ringBounds())
        plan.valueLayout?.let {
            obstacles.add(TemperatureGraphObstacleType.FETCH_DOT_VALUE, it.bounds)
        }
        plan.staleness?.let {
            obstacles.add(
                TemperatureGraphObstacleType.FETCH_DOT_AGE_RESERVATION,
                it.provisional.bounds,
            )
        }
    }

    fun draw(
        plan: Plan,
        input: Input,
        obstacles: TemperatureGraphObstacleRegistry,
    ) {
        val localDotPaint =
            Paint(input.paints.dotPaint).apply {
                color = TemperatureGraphStyle.tempToColor(plan.lastObservedTemp)
            }
        input.canvas.drawCircle(plan.clampedX, plan.fetchY, plan.dotRadius, localDotPaint)
        input.canvas.drawCircle(plan.clampedX, plan.fetchY, plan.dotRadius, input.paints.ringPaint)
        input.canvas.drawCircle(plan.clampedX, plan.fetchY, plan.outerRadius, input.paints.outerRingPaint)

        plan.valueLayout?.let { valueLayout ->
            val localValuePaint =
                Paint(input.paints.fetchDotValueTextPaint).apply {
                    textAlign = valueLayout.align
                }
            input.canvas.drawText(plan.valueLabel, valueLayout.x, valueLayout.y, localValuePaint)
        }

        // The provisional age rectangle reserved space for earlier label stages. It must not
        // participate in final placement or it collides with itself.
        obstacles.remove(TemperatureGraphObstacleType.FETCH_DOT_AGE_RESERVATION)
        val finalAgeY = drawStaleness(plan, input, obstacles)

        input.onResolved?.invoke(
            FetchDotDebug(
                observedAt = plan.observedAt,
                fetchDotX = plan.clampedX,
                fetchY = plan.fetchY,
                withinWindow = true,
                ageText =
                    if (plan.staleness != null) {
                        "${plan.valueLabel} (${plan.staleness.ageLabel})"
                    } else {
                        plan.valueLabel
                    },
                valueColor = input.paints.fetchDotValueTextPaint.color,
                stalenessColor = plan.staleness?.let { input.paints.stalenessTextPaint.color },
                stalenessLabelY = finalAgeY,
            ),
        )
    }

    private fun drawStaleness(
        plan: Plan,
        input: Input,
        obstacles: TemperatureGraphObstacleRegistry,
    ): Float? {
        val staleness = plan.staleness ?: return null
        val externalBounds = obstacles.bounds()
        val initial =
            resolveStalenessInitialLayout(
                clampedX = plan.clampedX,
                fetchY = plan.fetchY,
                dotRadius = plan.dotRadius,
                padding = staleness.padding,
                ageWidth = staleness.ageWidth,
                ascent = staleness.ascent,
                descent = staleness.descent,
                heightPx = input.heightPx,
                existingBounds = externalBounds,
                minorOverlapThreshold = staleness.minorOverlapThreshold,
            )
        val placeAbove = initial.placeAbove
        var ageBaselineY = initial.baselineY
        val bounds = RectF(initial.bounds)
        var collision =
            maximumOverlap(bounds, externalBounds) > staleness.minorOverlapThreshold
        var step = 0
        val bump =
            TemperatureGraphStyle.dpToPx(
                input.context,
                TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * input.labelScale,
            )
        while (collision && step < MAX_STALENESS_DISPLACEMENT_STEPS) {
            step++
            ageBaselineY += if (placeAbove) -bump else bump
            bounds.offsetTo(
                plan.clampedX - staleness.ageWidth / 2f,
                ageBaselineY + staleness.ascent,
            )
            collision =
                maximumOverlap(bounds, externalBounds) > staleness.minorOverlapThreshold
        }

        if (step > STALENESS_LEADER_LINE_MIN_STEPS) {
            val lineEndY = if (placeAbove) bounds.bottom else bounds.top
            val lineStartY =
                if (placeAbove) {
                    plan.fetchY - plan.dotRadius
                } else {
                    plan.fetchY + plan.dotRadius
                }
            input.canvas.drawLine(
                plan.clampedX,
                lineStartY,
                plan.clampedX,
                lineEndY,
                input.paints.actualLeaderLinePaint,
            )
        }

        input.canvas.drawText(
            staleness.ageLabel,
            plan.clampedX,
            ageBaselineY,
            input.paints.stalenessTextPaint,
        )
        obstacles.replace(
            removedType = TemperatureGraphObstacleType.FETCH_DOT_AGE_RESERVATION,
            finalType = TemperatureGraphObstacleType.FETCH_DOT_AGE,
            bounds = bounds,
        )
        return ageBaselineY
    }

    private fun resolveStalenessPlan(
        input: Input,
        clampedX: Float,
        fetchY: Float,
        dotRadius: Float,
        ringBounds: RectF,
        valueLayout: ValueLabelLayout?,
        ageLabel: String?,
    ): StalenessPlan? {
        ageLabel ?: return null
        val paint = input.paints.stalenessTextPaint
        val ageWidth = paint.measureText(ageLabel)
        val ascent = TemperatureGraphStyle.fontAscent(paint)
        val descent = TemperatureGraphStyle.fontDescent(paint)
        val padding =
            TemperatureGraphStyle.dpToPx(
                input.context,
                TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * input.labelScale,
            )
        val minorOverlapThreshold = paint.textSize * STALENESS_MINOR_OVERLAP_RATIO
        val provisional =
            resolveStalenessInitialLayout(
                clampedX = clampedX,
                fetchY = fetchY,
                dotRadius = dotRadius,
                padding = padding,
                ageWidth = ageWidth,
                ascent = ascent,
                descent = descent,
                heightPx = input.heightPx,
                existingBounds = buildList {
                    add(ringBounds)
                    valueLayout?.let { add(it.bounds) }
                },
                minorOverlapThreshold = minorOverlapThreshold,
            )
        return StalenessPlan(
            ageLabel = ageLabel,
            ageWidth = ageWidth,
            ascent = ascent,
            descent = descent,
            padding = padding,
            minorOverlapThreshold = minorOverlapThreshold,
            provisional = provisional,
        )
    }

    private fun resolveAgeLabel(input: Input): String? {
        val ageMinutes =
            input.fetchTime?.let {
                Duration.between(it, input.currentTime).toMinutes()
            } ?: 0L
        val hoursSpan =
            Duration.between(input.hours.first().dateTime, input.hours.last().dateTime).toHours()
        return TemperatureGraphStyle.formatAgeLabel(ageMinutes, hoursSpan)
    }

    /**
     * Picks RIGHT, then LEFT, then ABOVE. BELOW is reserved for the staleness label.
     */
    private fun resolveValueLabelLayout(
        clampedX: Float,
        fetchY: Float,
        dotRadius: Float,
        valueWidth: Float,
        sideGap: Float,
        aboveGap: Float,
        widthPx: Int,
        baselineOffset: Float,
        ascent: Float,
        descent: Float,
    ): ValueLabelLayout? {
        if (clampedX + dotRadius + sideGap + valueWidth <= widthPx) {
            val x = clampedX + dotRadius + sideGap
            val y = fetchY + baselineOffset
            return ValueLabelLayout(
                x,
                y,
                RectF(x, y + ascent, x + valueWidth, y + descent),
                Paint.Align.LEFT,
            )
        }
        if (clampedX - dotRadius - sideGap - valueWidth >= 0) {
            val x = clampedX - dotRadius - sideGap
            val y = fetchY + baselineOffset
            return ValueLabelLayout(
                x,
                y,
                RectF(x - valueWidth, y + ascent, x, y + descent),
                Paint.Align.RIGHT,
            )
        }
        if (fetchY - dotRadius - aboveGap + ascent >= 0) {
            val x = clampedX
            val y = fetchY - dotRadius - aboveGap
            return ValueLabelLayout(
                x,
                y,
                RectF(x - valueWidth / 2f, y + ascent, x + valueWidth / 2f, y + descent),
                Paint.Align.CENTER,
            )
        }
        return null
    }

    private fun resolveStalenessInitialLayout(
        clampedX: Float,
        fetchY: Float,
        dotRadius: Float,
        padding: Float,
        ageWidth: Float,
        ascent: Float,
        descent: Float,
        heightPx: Int,
        existingBounds: List<RectF>,
        minorOverlapThreshold: Float,
    ): StalenessInitialLayout {
        var placeAbove = false
        var baselineY = fetchY + dotRadius + padding - ascent
        val bounds =
            RectF(
                clampedX - ageWidth / 2f,
                baselineY + ascent,
                clampedX + ageWidth / 2f,
                baselineY + descent,
            )
        val collision =
            maximumOverlap(bounds, existingBounds) > minorOverlapThreshold
        if (collision || bounds.bottom > heightPx) {
            placeAbove = true
            baselineY = fetchY - dotRadius - padding - descent
            bounds.offsetTo(clampedX - ageWidth / 2f, baselineY + ascent)
        }
        return StalenessInitialLayout(baselineY, bounds, placeAbove)
    }

    private fun maximumOverlap(
        bounds: RectF,
        existingBounds: List<RectF>,
    ): Float =
        GraphLabelPlacementUtils.maxVerticalOverlap(
            GraphRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            existingBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
        )
}
