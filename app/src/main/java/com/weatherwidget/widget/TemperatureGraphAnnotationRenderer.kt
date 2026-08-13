package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.GhostLineLabel
import com.weatherwidget.shared.graph.GraphEmptySpaceFinder
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.LabelPlacementDebug
import com.weatherwidget.shared.graph.LabelTextMetrics
import com.weatherwidget.shared.graph.TemperatureLabelEngine
import com.weatherwidget.shared.graph.TemperatureRole
import com.weatherwidget.shared.graph.ForecastDeltaLabel
import com.weatherwidget.shared.util.TempUtils
import com.weatherwidget.util.WeatherConditionColors
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

internal object TemperatureGraphAnnotationRenderer {
    private const val TAG = "TempGraphRenderer"
    private const val X_COORDINATE_MATCH_TOLERANCE = 0.5f
    private const val FORECAST_DELTA_LABEL_PAD_DP = 6f
    private const val DOMINANT_STATION_LABEL_PAD_DP = 6f
    private const val GHOST_LINE_LABEL_PAD_DP = 4f
    private const val GHOST_LINE_LABEL_GAP_DP = 2.5f

    private val TEMPERATURE_ROLES_OF_INTEREST: Set<TemperatureRole> =
        setOf(
            TemperatureRole.ACTUAL_END,
            TemperatureRole.ACTUAL_HIGH,
            TemperatureRole.ACTUAL_LOW,
            TemperatureRole.HIGH,
            TemperatureRole.LOW,
            TemperatureRole.LOCAL,
            TemperatureRole.START,
            TemperatureRole.END,
        )

    data class Input(
        val context: Context,
        val canvas: Canvas,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val labelScale: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val minTemp: Float,
        val tempRange: Float,
        val currentTime: LocalDateTime,
        val lastObservedTemp: Float?,
        val appliedDelta: Float?,
        val observedAt: Long?,
        val series: TemperatureGraphSeriesGeometry,
        val paints: PaintSet,
        val obstacles: TemperatureGraphObstacleRegistry,
        val useCelsius: Boolean,
        val onLabelPlaced: ((LabelPlacementDebug) -> Unit)?,
        val onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)?,
    ) {
        fun tempToY(temp: Float): Float =
            TemperatureGraphStyle.tempToY(
                temp,
                graphTop,
                graphHeight,
                minTemp,
                tempRange,
            )
    }

    fun placeTemperatureLabels(
        input: Input,
        hours: List<HourData>,
        drawnIconBounds: List<RectF>,
        fetchDotBounds: List<RectF>,
        numColumns: Int,
    ) {
        val metricsSourcePaint = input.paints.actualTempLabelTextPaint
        val textMetrics =
            object : LabelTextMetrics {
                override fun width(
                    text: String,
                    isFuture: Boolean,
                ): Float {
                    val measurePaint =
                        if (isFuture) {
                            input.paints.forecastTempLabelTextPaint
                        } else {
                            input.paints.actualTempLabelTextPaint
                        }
                    return measurePaint.measureText(text)
                }

                override val ascent: Float =
                    TemperatureGraphStyle.fontAscent(metricsSourcePaint)
                override val descent: Float =
                    TemperatureGraphStyle.fontDescent(metricsSourcePaint)
            }
        val neutralIconBounds =
            drawnIconBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }
        val fetchDotHardBounds =
            fetchDotBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }
        val placements =
            TemperatureLabelEngine.computePlacements(
                hours = hours,
                widthPx = input.widthPx,
                heightPx = input.heightPx,
                density = input.density,
                originalPoints = input.series.originalPoints,
                forecastPoints = input.series.forecastPoints,
                actualVisiblePoints = input.series.actualVisiblePoints,
                transitionX = input.series.transitionX,
                fetchDotX = input.series.fetchDotX,
                lastObservedTemp = input.lastObservedTemp,
                observedAt = input.observedAt,
                effectiveActualEndIndex = input.series.effectiveActualEndIndex,
                fetchTime = input.series.fetchTime,
                numColumns = numColumns,
                tempToY = input::tempToY,
                metrics = textMetrics,
                drawnIconBounds = neutralIconBounds,
                reservedHardBounds = fetchDotHardBounds,
                useCelsius = input.useCelsius,
            )

        placements.forEach { placement ->
            val labelPaint =
                if (placement.isFuture) {
                    val hour = hours[placement.index.coerceAtMost(hours.lastIndex)]
                    Paint(input.paints.forecastTempLabelTextPaint).also {
                        it.color =
                            WeatherConditionColors.forecastColor(
                                hour.isSunny,
                                hour.isRainy,
                                hour.isMixed,
                                hour.isNight,
                                hour.isTwilight,
                            )
                    }
                } else {
                    input.paints.actualTempLabelTextPaint
                }
            val leaderLinePaint =
                if (placement.isFuture) {
                    Paint(input.paints.forecastLeaderLinePaint).also {
                        it.color = TemperatureGraphStyle.withAlpha(labelPaint.color, 80)
                    }
                } else {
                    input.paints.actualLeaderLinePaint
                }
            if (placement.drawLeaderLine) {
                input.canvas.drawLine(
                    placement.x,
                    placement.leaderFromY,
                    placement.x,
                    placement.leaderToY,
                    leaderLinePaint,
                )
            }
            input.canvas.drawText(
                placement.text,
                placement.x,
                placement.baselineY,
                labelPaint,
            )

            val seriesLabel = if (placement.isFuture) "forecast" else "actual"
            val debug =
                LabelPlacementDebug(
                    index = placement.index,
                    role = placement.role,
                    temperature = placement.displayTemperature,
                    rawTemperature = placement.rawTemperature,
                    x = placement.x,
                    y = placement.baselineY,
                    placedAbove = placement.placedAbove,
                    series = seriesLabel,
                    colorFamily = seriesLabel,
                    hexColor = TemperatureGraphStyle.formatColorHex(labelPaint.color),
                    reason = placement.reason,
                    displacementSteps = placement.displacementSteps,
                )
            if (
                placement.role in TEMPERATURE_ROLES_OF_INTEREST &&
                Log.isLoggable(TAG, Log.VERBOSE)
            ) {
                Log.v(TAG, "LabelPlacementDebug: $debug")
            }
            input.onLabelPlaced?.invoke(debug)

            val textWidth = labelPaint.measureText(placement.text)
            input.obstacles.add(
                TemperatureGraphObstacleType.TEMPERATURE_LABEL,
                RectF(
                    placement.x - textWidth / 2f,
                    placement.baselineY + textMetrics.ascent,
                    placement.x + textWidth / 2f,
                    placement.baselineY + textMetrics.descent,
                ),
            )
        }
    }

    fun placeDayLabels(
        input: Input,
        hours: List<HourData>,
    ) {
        val fontMetrics = input.paints.dayLabelTextPaint.fontMetrics ?: Paint.FontMetrics()
        val dayLabelTextHeight = fontMetrics.descent - fontMetrics.ascent
        val dayYTop = input.graphTop + dayLabelTextHeight
        val dayYMid = (input.graphTop + input.graphBottom) / 2f
        val dayYBottom =
            input.heightPx -
                TemperatureGraphStyle.dpToPx(
                    input.context,
                    TemperatureGraphStyle.DAY_LABEL_BOTTOM_PADDING_DP,
                )
        val today = input.currentTime.toLocalDate()
        val leftDate = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText =
            hours
                .first()
                .dateTime
                .dayOfWeek
                .getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val rightText =
            hours
                .last()
                .dateTime
                .dayOfWeek
                .getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val leftWidth =
            dayPaint(input, leftDate == today).measureText(leftText)
        val rightWidth =
            dayPaint(input, rightDate == today).measureText(rightText)
        val candidates =
            listOf(
                DayCandidate(leftDate, leftWidth / 2f, leftText),
                DayCandidate(rightDate, input.widthPx - rightWidth / 2f, rightText),
            )

        candidates.forEachIndexed { index, candidate ->
            val isToday = candidate.date == today
            val paint = dayPaint(input, isToday)
            val textWidth = paint.measureText(candidate.text)

            fun bounds(y: Float) =
                RectF(
                    candidate.x - textWidth / 2f,
                    y + fontMetrics.ascent,
                    candidate.x + textWidth / 2f,
                    y + fontMetrics.descent,
                )

            val topBounds = bounds(dayYTop)
            if (!collides(input, topBounds)) {
                drawDayLabel(input, candidate, index, dayYTop, "TOP", isToday, paint, topBounds)
                return@forEachIndexed
            }
            val middleBounds = bounds(dayYMid)
            if (!collides(input, middleBounds)) {
                drawDayLabel(
                    input,
                    candidate,
                    index,
                    dayYMid,
                    "MIDDLE",
                    isToday,
                    paint,
                    middleBounds,
                )
                return@forEachIndexed
            }
            val bottomBounds = bounds(dayYBottom)
            drawDayLabel(
                input,
                candidate,
                index,
                dayYBottom,
                if (collides(input, bottomBounds)) "BOTTOM_FORCED_OVERLAP" else "BOTTOM",
                isToday,
                paint,
                bottomBounds,
            )
        }
    }

    fun placeForecastDeltaLabel(
        input: Input,
        hours: List<HourData>,
        forecastDelta: Float?,
    ) {
        val delta = forecastDelta ?: return
        val fetchDotX = input.series.fetchDotX
        if (fetchDotX == null || fetchDotX < 0f || fetchDotX > input.widthPx.toFloat()) return
        val currentTemp = input.lastObservedTemp ?: return
        if (hours.size < 2) return
        val spanHours = Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
        val paint = input.paints.stalenessTextPaint
        val text = ForecastDeltaLabel.format(delta, input.useCelsius)
        val ghostVisible = ghostLineVisible(input, hours)
        val placement =
            ForecastDeltaLabel.place(
                delta = delta,
                currentTemp = currentTemp,
                spanHours = spanHours,
                plot = GraphRect(0f, input.graphTop, input.widthPx.toFloat(), input.graphBottom),
                drawnBounds = input.graphObstacles(),
                curveYsAt = { visibleCurveYs(input, it, ghostVisible) },
                metrics =
                    ForecastDeltaLabel.Metrics(
                        width = paint.measureText(text),
                        ascent = TemperatureGraphStyle.fontAscent(paint),
                        descent = TemperatureGraphStyle.fontDescent(paint),
                    ),
                padPx = TemperatureGraphStyle.dpToPx(input.context, FORECAST_DELTA_LABEL_PAD_DP),
                useCelsius = input.useCelsius,
            ) ?: return
        val labelPaint =
            Paint(paint).apply {
                color = placement.colorArgb
                textAlign = Paint.Align.CENTER
            }
        input.canvas.drawText(
            placement.text,
            placement.centerX,
            placement.baselineY,
            labelPaint,
        )
        input.obstacles.add(
            TemperatureGraphObstacleType.FORECAST_DELTA,
            placement.box.toRectF(),
        )
    }

    /**
     * Names the station dominating the observation blend, e.g. `knuq 73.4°`, wherever the plot has room.
     *
     * Drawn with the staleness paint UNRECOLORED — that paint is already the observed-line color, which
     * is exactly what this label explains. (The delta label recolors the same paint to the thermostat
     * gradient; leaving this one alone is what keeps the two readable as different things.)
     */
    fun placeDominantStationLabel(
        input: Input,
        hours: List<HourData>,
        dominantStationLabel: DominantStationLabel.LabelText?,
    ) {
        val text = dominantStationLabel?.fullText?.takeIf { it.isNotBlank() }
        val spanHours =
            if (hours.size >= 2) {
                Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
            } else {
                0L
            }
        val reason: String
        if (dominantStationLabel == null) {
            reason = "no_text"
        } else if (hours.size < 2) {
            reason = "too_few_hours"
        } else if (spanHours > DominantStationLabel.MAX_HOURS_SPAN) {
            reason = "span_too_wide"
        } else {
            val ghostVisible = ghostLineVisible(input, hours)
            val tempPaint = input.paints.dominantTempTextPaint
            val smallPaint = input.paints.stalenessTextPaint
            val segmentWidths =
                dominantStationLabel.segments.map { segment ->
                    val paint =
                        if (segment.part == DominantStationLabel.Part.TEMPERATURE) tempPaint else smallPaint
                    paint.measureText(segment.text)
                }
            val totalWidth = segmentWidths.sum()
            val placement =
                DominantStationLabel.place(
                    text = text,
                    spanHours = spanHours,
                    plot = GraphRect(0f, input.graphTop, input.widthPx.toFloat(), input.graphBottom),
                    drawnBounds = input.graphObstacles(),
                    curveYsAt = { visibleCurveYs(input, it, ghostVisible) },
                    metrics =
                        GraphEmptySpaceFinder.Metrics(
                            width = totalWidth,
                            ascent = TemperatureGraphStyle.fontAscent(tempPaint),
                            descent = TemperatureGraphStyle.fontDescent(tempPaint),
                        ),
                    padPx = TemperatureGraphStyle.dpToPx(input.context, DOMINANT_STATION_LABEL_PAD_DP),
                )
            if (placement != null) {
                var x = placement.box.left
                dominantStationLabel.segments.forEachIndexed { index, segment ->
                    val paint =
                        if (segment.part == DominantStationLabel.Part.TEMPERATURE) {
                            Paint(tempPaint).apply { textAlign = Paint.Align.LEFT }
                        } else {
                            Paint(smallPaint).apply { textAlign = Paint.Align.LEFT }
                        }
                    input.canvas.drawText(segment.text, x, placement.baselineY, paint)
                    x += segmentWidths[index]
                }
                input.obstacles.add(
                    TemperatureGraphObstacleType.DOMINANT_STATION,
                    placement.box.toRectF(),
                )
                reason = "drawn"
            } else {
                reason = "no_empty_band"
            }
        }
        // Mirrors the desktop DominantStationDiag. Placement-side reasons only: the upstream
        // no_contribution/synthetic/format_null gate lives in TemperatureStateResolver.
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(
                TAG,
                "DominantStationDiag: reason=$reason spanH=$spanHours maxSpanH=${DominantStationLabel.MAX_HOURS_SPAN} " +
                    "text=${text ?: "null"} drawnBounds=${input.obstacles.bounds().size} " +
                    "plotW=${input.widthPx} plotH=${(input.graphBottom - input.graphTop).roundToInt()}",
            )
        }
    }

    fun placeGhostLineLabel(
        input: Input,
        hours: List<HourData>,
    ) {
        if (
            !TemperatureGraphSeriesRenderer.shouldRenderGhostLine(
                TemperatureGraphSeriesRenderer.GhostGateInput(
                    hours = hours,
                    currentTime = input.currentTime,
                    fetchDotX = input.series.fetchDotX,
                    widthPx = input.widthPx,
                    nowIndicatorVisible = input.series.nowIndicatorVisible,
                    appliedDelta = input.appliedDelta,
                ),
            )
        ) {
            return
        }
        if (hours.size < 2 || input.series.expectedPoints.size != hours.size) return
        val fetchDotX = requireNotNull(input.series.fetchDotX)
        val spanHours = Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
        val candidates =
            hours.indices.mapNotNull { index ->
                val (x, ghostY) = input.series.expectedPoints[index]
                if (x <= fetchDotX + X_COORDINATE_MATCH_TOLERANCE) return@mapNotNull null
                val expectedTemp = input.series.expectedTemps[index]
                if (!expectedTemp.isFinite()) return@mapNotNull null
                GhostLineLabel.Candidate(
                    x = x,
                    ghostY = ghostY,
                    expectedTemp =
                        if (input.useCelsius) {
                            TempUtils.fahrenheitToCelsius(expectedTemp)
                        } else {
                            expectedTemp
                        },
                    hasHourLabel = hours[index].showLabel,
                )
            }
        if (candidates.isEmpty()) return

        val paint = input.paints.ghostLineLabelPaint
        val placements =
            GhostLineLabel.placeAll(
                candidates = candidates,
                spanHours = spanHours,
                plot = GraphRect(0f, input.graphTop, input.widthPx.toFloat(), input.graphBottom),
                ghostLineStartX = fetchDotX,
                drawnBounds = input.graphObstacles(),
                curveYAt = { sampleVisibleCurveY(input, it) },
                metrics =
                    GhostLineLabel.Metrics(
                        width =
                            candidates.maxOf {
                                paint.measureText(GhostLineLabel.format(it.expectedTemp))
                            },
                        ascent = TemperatureGraphStyle.fontAscent(paint),
                        descent = TemperatureGraphStyle.fontDescent(paint),
                    ),
                padPx = TemperatureGraphStyle.dpToPx(input.context, GHOST_LINE_LABEL_PAD_DP),
                gapPx = TemperatureGraphStyle.dpToPx(input.context, GHOST_LINE_LABEL_GAP_DP),
            )
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(
                TAG,
                "GhostLineLabel: span=${spanHours}h candidates=[" +
                    candidates.joinToString {
                        "${it.x.roundToInt()}@${GhostLineLabel.format(it.expectedTemp)}" +
                            if (it.hasHourLabel) "*" else ""
                    } +
                    "] -> placed=${placements.size}: [" +
                    placements.joinToString { "${it.centerX.roundToInt()}=${it.text}" } +
                    "]",
            )
        }
        placements.forEach { placement ->
            input.canvas.drawText(
                placement.text,
                placement.centerX,
                placement.baselineY,
                paint,
            )
            input.obstacles.add(
                TemperatureGraphObstacleType.GHOST_LABEL,
                placement.box.toRectF(),
            )
        }
    }

    private data class DayCandidate(
        val date: LocalDate,
        val x: Float,
        val text: String,
    )

    private fun drawDayLabel(
        input: Input,
        candidate: DayCandidate,
        index: Int,
        baselineY: Float,
        placement: String,
        isToday: Boolean,
        paint: Paint,
        bounds: RectF,
    ) {
        input.canvas.drawText(candidate.text, candidate.x, baselineY, paint)
        input.obstacles.add(TemperatureGraphObstacleType.DAY_LABEL, bounds)
        input.onDayLabelPlaced?.invoke(
            DayLabelPlacementDebug(
                side = if (index == 0) "LEFT" else "RIGHT",
                dayText = candidate.text,
                date = candidate.date,
                x = candidate.x,
                y = baselineY,
                placement = placement,
                isToday = isToday,
            ),
        )
    }

    private fun dayPaint(
        input: Input,
        isToday: Boolean,
    ): Paint =
        if (isToday) {
            input.paints.todayDayLabelPaint
        } else {
            input.paints.dayLabelTextPaint
        }

    private fun collides(
        input: Input,
        bounds: RectF,
    ): Boolean =
        input.obstacles.bounds().any { RectF.intersects(it, bounds) }

    /**
     * Every temperature line actually drawn at [x], for free-floating-label collision tests.
     *
     * Distinct from [sampleVisibleCurveY], which models the graph as ONE curve that switches from
     * observed to forecast at the transition point. That model is right for the ghost-line geometry but
     * wrong for collision: both lines are painted across their own x ranges at the same time, so a
     * one-answer sampler reports open air where the other line is. The dominant-station label shipped
     * sitting on the forecast dashes because of exactly that.
     *
     * [ghostVisible] must be the caller's own ghost-line gate result — the expected line is only painted
     * when the gate passes, and reserving space for an unpainted line loses slots for nothing.
     */
    private fun visibleCurveYs(
        input: Input,
        x: Float,
        ghostVisible: Boolean,
    ): List<Float> {
        val ys = mutableListOf<Float>()
        // The forecast line spans the whole window.
        if (input.series.forecastPoints.isNotEmpty()) {
            TemperatureGraphSeriesResolver.interpolateYAtX(input.series.forecastPoints, x)?.let(ys::add)
        }
        // The observed line stops at the transition (the fetch dot).
        val transitionX = input.series.transitionX
        if (transitionX != null && x <= transitionX && input.series.actualVisiblePoints.isNotEmpty()) {
            TemperatureGraphSeriesResolver.interpolateYAtX(input.series.actualVisiblePoints, x)?.let(ys::add)
        }
        // The ghost (expected) line starts at the fetch dot and runs to the right edge.
        val fetchDotX = input.series.fetchDotX
        if (ghostVisible && fetchDotX != null && x >= fetchDotX && input.series.expectedPoints.isNotEmpty()) {
            TemperatureGraphSeriesResolver.interpolateYAtX(input.series.expectedPoints, x)?.let(ys::add)
        }
        return ys
    }

    /** The ghost-line gate, so [visibleCurveYs] knows whether the expected line is on the canvas. */
    private fun ghostLineVisible(input: Input, hours: List<HourData>): Boolean =
        TemperatureGraphSeriesRenderer.shouldRenderGhostLine(
            TemperatureGraphSeriesRenderer.GhostGateInput(
                hours = hours,
                currentTime = input.currentTime,
                fetchDotX = input.series.fetchDotX,
                widthPx = input.widthPx,
                nowIndicatorVisible = input.series.nowIndicatorVisible,
                appliedDelta = input.appliedDelta,
            ),
        )

    private fun sampleVisibleCurveY(
        input: Input,
        x: Float,
    ): Float? {
        val useActual =
            input.series.transitionX != null &&
                x <= input.series.transitionX &&
                input.series.actualVisiblePoints.isNotEmpty()
        val points =
            if (useActual) {
                input.series.actualVisiblePoints
            } else {
                input.series.forecastPoints
            }
        if (points.isEmpty()) return null
        return TemperatureGraphSeriesResolver.interpolateYAtX(points, x)
    }

    private fun Input.graphObstacles(): List<GraphRect> =
        obstacles.bounds().map { GraphRect(it.left, it.top, it.right, it.bottom) }

    private fun GraphRect.toRectF(): RectF = RectF(left, top, right, bottom)
}
