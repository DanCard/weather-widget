package com.weatherwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.LabelPlacementDebug
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

object TemperatureGraphRenderer {
    private const val TAG = "TempGraphRenderer"

    private const val X_COORDINATE_MATCH_TOLERANCE = 0.5f
    private const val SECONDS_PER_HOUR = 3600f

    private fun ensurePaints(context: Context, labelScale: Float): PaintSet = TemperatureGraphStyle.ensurePaints(context, labelScale)
    private fun dpToPx(context: Context, dp: Float): Float = TemperatureGraphStyle.dpToPx(context, dp)

    @androidx.annotation.VisibleForTesting
    internal fun resolveForecastSegmentColors(
        hours: List<HourData>,
        segments: List<AndroidCurvePathBuilder.IndexedCurvePath>,
    ): List<Int> =
        TemperatureGraphSeriesRenderer.resolveForecastSegmentColors(hours, segments)

    private fun drawHourLabelsAndIcons(
        context: Context,
        canvas: Canvas,
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        labelScale: Float,
        originalPoints: List<Pair<Float, Float>>,
        paints: PaintSet,
        iconSize: Int,
        numColumns: Int,
    ): List<RectF> {
        val drawnIconBounds = mutableListOf<RectF>()
        val minHourLabelSpacing =
            dpToPx(
                context,
                TemperatureGraphStyle.HOUR_LABEL_SPACING_DP * labelScale,
            )
        val footerPlan = HourlyFooterRenderer.planHourLabels(
            items = hours,
            points = originalPoints,
            widthPx = widthPx,
            heightPx = heightPx,
            minHourLabelSpacing = minHourLabelSpacing,
            hourLabelTextPaint = paints.hourLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
            showLabel = { it.showLabel },
            labelText = { it.label },
            iconSize = iconSize.toFloat(),
            iconTextGapDp = HourlyFooterRenderer.iconGapDp(numColumns),
            hasIcon = { it.iconRes != null },
            isDateLabel = { it.isDateLabel },
            iconsAvailable = true,
        )
        HourlyFooterRenderer.drawPlan(
            canvas = canvas,
            plan = footerPlan,
            hourLabelTextPaint = paints.hourLabelTextPaint,
        ) { index, iconRect ->
            val hour = hours[index]
            val iconRes = hour.iconRes ?: return@drawPlan
            drawnIconBounds.add(iconRect)
            HourlyFooterRenderer.drawHourIcon(
                context = context,
                canvas = canvas,
                iconRes = iconRes,
                iconRect = iconRect,
                isRainy = hour.isRainy,
                isMixed = hour.isMixed,
                isNight = hour.isNight,
                isTwilight = hour.isTwilight,
                isSunny = hour.isSunny,
            )
        }
        return drawnIconBounds
    }

    fun renderGraph(
        context: Context,
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        appliedDelta: Float? = null,
        observedAt: Long? = null,
        lastObservedTemp: Float? = null,
        numColumns: Int = 0,
        job: Job? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onGhostLineDebug: ((GhostLineDebug) -> Unit)? = null,
        onPointsResolved: ((PointsDebug) -> Unit)? = null,
        onActualLineResolved: ((ActualLineDebug) -> Unit)? = null,
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
        useCelsius: Boolean,
        /** Pre-formatted `knuq 73.4° @ 5:15 pm` (with segments for mixed sizing); null suppresses the annotation entirely. */
        dominantStationLabel: DominantStationLabel.LabelText? = null,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphFailureWatermarkRenderer.draw(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
            }
            return bitmap
        }

        val timings = RenderTimings()
        timings.mark("start")

        val labelScale = bitmapScale.coerceAtMost(1f)
        val paints = ensurePaints(context, labelScale)
        val density = context.resources.displayMetrics.density
        timings.mark("paints")

        val (minTemp, maxTemp, tempRange) = GraphLayout.computeScaling(hours)
        val footerIconSize = HourlyFooterRenderer.iconSize(paints.hourLabelTextPaint)
        val layout = GraphLayout.computeLayout(context, heightPx, labelScale, footerIconSize)
        timings.mark("layout")

        val minTimeEpoch = hours.firstOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val maxTimeEpoch = hours.lastOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val timeRangeHours = if (maxTimeEpoch > minTimeEpoch) (maxTimeEpoch - minTimeEpoch) / SECONDS_PER_HOUR else hours.size.toFloat() - 1f
        val hourWidth = widthPx.toFloat() / timeRangeHours.coerceAtLeast(1f)

        val series =
            TemperatureGraphSeriesResolver.resolve(
                TemperatureGraphSeriesResolver.Input(
                    hours = hours,
                    minTemp = minTemp,
                    tempRange = tempRange,
                    graphTop = layout.graphTop,
                    graphHeight = layout.graphHeight,
                    graphBottom = layout.graphBottom,
                    hourWidth = hourWidth,
                    minTimeEpoch = minTimeEpoch,
                    currentTime = currentTime,
                    appliedDelta = appliedDelta,
                    observedAt = observedAt,
                    lastObservedTemp = lastObservedTemp,
                    widthPx = widthPx,
                    job = job,
                    onPointsResolved = onPointsResolved,
                ),
            )
        timings.mark("points")

        job?.ensureActive()
        val obstacles = TemperatureGraphObstacleRegistry()

        onActualLineResolved?.invoke(
            ActualLineDebug(
                endX = series.actualVisiblePoints.lastOrNull()?.first,
                endY = series.actualVisiblePoints.lastOrNull()?.second,
                pointCount = series.actualVisiblePoints.size,
                anchoredToFetchDot = series.fetchDotX != null &&
                    series.actualVisiblePoints.lastOrNull()?.first?.let { abs(it - series.fetchDotX) <= X_COORDINATE_MATCH_TOLERANCE } == true,
            ),
        )

        // Draw Now Line early so it's behind all labels and curves (lowest z-order)
        HourlyIndicatorRenderer.drawNowLine(
            canvas,
            if (series.nowIndicatorVisible) series.nowX else null,
            layout.graphTop,
            layout.graphHeight,
            paints.currentTimePaint
        )

        TemperatureGraphSeriesRenderer.draw(
            TemperatureGraphSeriesRenderer.Input(
                context = context,
                canvas = canvas,
                widthPx = widthPx,
                heightPx = heightPx,
                labelScale = labelScale,
                graphTop = layout.graphTop,
                graphBottom = layout.graphBottom,
                minTemp = minTemp,
                maxTemp = maxTemp,
                tempRange = tempRange,
                currentTime = currentTime,
                appliedDelta = appliedDelta,
                lastObservedTemp = lastObservedTemp,
                hours = hours,
                series = series,
                paints = paints,
                onGhostLineDebug = onGhostLineDebug,
            ),
        )
        timings.mark("curves")

        val drawnIconBounds =
            drawHourLabelsAndIcons(
                context = context,
                canvas = canvas,
                hours = hours,
                widthPx = widthPx,
                heightPx = heightPx,
                labelScale = labelScale,
                originalPoints = series.originalPoints,
                paints = paints,
                iconSize = layout.iconSize,
                numColumns = numColumns,
            )
        obstacles.addAll(TemperatureGraphObstacleType.ICON, drawnIconBounds)
        timings.mark("icons")

        val annotationInput =
            TemperatureGraphAnnotationRenderer.Input(
                context = context,
                canvas = canvas,
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                labelScale = labelScale,
                graphTop = layout.graphTop,
                graphBottom = layout.graphBottom,
                graphHeight = layout.graphHeight,
                minTemp = minTemp,
                tempRange = tempRange,
                currentTime = currentTime,
                lastObservedTemp = lastObservedTemp,
                appliedDelta = appliedDelta,
                observedAt = observedAt,
                series = series,
                paints = paints,
                obstacles = obstacles,
                useCelsius = useCelsius,
                onLabelPlaced = onLabelPlaced,
                onDayLabelPlaced = onDayLabelPlaced,
            )
        val fetchDotInput =
            TemperatureFetchDotRenderer.Input(
                context = context,
                canvas = canvas,
                widthPx = widthPx,
                heightPx = heightPx,
                labelScale = labelScale,
                graphTop = layout.graphTop,
                graphHeight = layout.graphHeight,
                minTemp = minTemp,
                tempRange = tempRange,
                fetchTime = series.fetchTime,
                fetchDotX = series.fetchDotX,
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                currentTime = currentTime,
                hours = hours,
                paints = paints,
                useCelsius = useCelsius,
                onResolved = onFetchDotResolved,
            )
        val fetchDotPlan = TemperatureFetchDotRenderer.plan(fetchDotInput)
        fetchDotPlan?.let { TemperatureFetchDotRenderer.reserve(it, obstacles) }
        val fetchDotReservationBounds = fetchDotPlan?.reservationBounds.orEmpty()
        TemperatureGraphAnnotationRenderer.placeTemperatureLabels(
            input = annotationInput,
            hours = hours,
            drawnIconBounds = drawnIconBounds,
            fetchDotBounds = fetchDotReservationBounds,
            numColumns = numColumns,
        )
        TemperatureGraphAnnotationRenderer.placeDayLabels(annotationInput, hours)
        timings.mark("labels")

        fetchDotPlan?.let {
            TemperatureFetchDotRenderer.draw(
                plan = it,
                input = fetchDotInput,
                obstacles = obstacles,
            )
        }

        // Order is a priority ladder over the same free space: the delta claims first (center-first
        // anchors), the station label second (edge-first), and the ghost labels route around both.
        TemperatureGraphAnnotationRenderer.placeForecastDeltaLabel(
            annotationInput,
            hours,
            appliedDelta,
        )
        TemperatureGraphAnnotationRenderer.placeDominantStationLabel(
            annotationInput,
            hours,
            dominantStationLabel,
        )
        TemperatureGraphAnnotationRenderer.placeGhostLineLabel(annotationInput, hours)

        HourlyIndicatorRenderer.drawNowIndicator(
            canvas = canvas,
            nowX = if (series.nowIndicatorVisible) series.nowX else null,
            graphTop = layout.graphTop,
            graphHeight = layout.graphHeight,
            currentTimePaint = paints.currentTimePaint,
            nowLabelTextPaint = paints.nowLabelTextPaint,
            drawnBounds = obstacles.bounds(),
            drawLine = false,
            dpToPx = { dpToPx(context, it) }
        )
        timings.mark("decorations")

        timings.log(widthPx, heightPx, hours.size, TAG)

        if (showErrorWatermark) {
            val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
            GraphFailureWatermarkRenderer.draw(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
        }

        return bitmap
    }
}
