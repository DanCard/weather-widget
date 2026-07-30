package com.weatherwidget.widget

import android.graphics.Path
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.HourlyTimelineGeometry
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

internal object TemperatureGraphSeriesResolver {
    private const val X_COORDINATE_MATCH_TOLERANCE = 0.5f
    private const val MIN_INTERPOLATION_SPAN = 0.0001f
    private const val TRANSITION_CLIP_EXTRA_DP = 1f
    private const val SECONDS_PER_HOUR = 3600f

    data class Input(
        val hours: List<HourData>,
        val minTemp: Float,
        val tempRange: Float,
        val graphTop: Float,
        val graphHeight: Float,
        val graphBottom: Float,
        val hourWidth: Float,
        val minTimeEpoch: Long,
        val currentTime: LocalDateTime,
        val appliedDelta: Float?,
        val observedAt: Long?,
        val lastObservedTemp: Float?,
        val widthPx: Int,
        val job: Job?,
        val onPointsResolved: ((PointsDebug) -> Unit)?,
    )

    fun resolve(input: Input): TemperatureGraphSeriesGeometry {
        input.job?.ensureActive()
        val effectiveDelta = input.appliedDelta ?: 0f
        val points = resolvePoints(input, effectiveDelta)
        val (expectedPath, expectedFillPath) =
            AndroidCurvePathBuilder.buildSmoothCurveAndFillPaths(
                points.expected,
                input.graphBottom,
            )
        val forecastSegmentPaths =
            AndroidCurvePathBuilder.buildPerSegmentPaths(points.forecast)
        val timeline = resolveTimeline(input, points.original)
        val actual =
            resolveActualSeries(
                input = input,
                originalPoints = points.original,
                fetchDotX = timeline.fetchDotX,
                nowX = timeline.nowX,
            )

        return TemperatureGraphSeriesGeometry(
            forecastTemps = points.forecastTemps,
            expectedTemps = points.expectedTemps,
            originalPoints = points.original,
            forecastPoints = points.forecast,
            expectedPoints = points.expected,
            actualPath = actual.path,
            actualVisiblePoints = actual.visiblePoints,
            expectedPath = expectedPath,
            expectedFillPath = expectedFillPath,
            forecastSegmentPaths = forecastSegmentPaths,
            nowX = timeline.nowX,
            nowIndicatorVisible = timeline.nowIndicatorVisible,
            fetchTime = timeline.fetchTime,
            fetchDotX = timeline.fetchDotX,
            transitionX = actual.transitionX,
            effectiveActualEndIndex = actual.effectiveEndIndex,
        )
    }

    private fun resolvePoints(
        input: Input,
        effectiveDelta: Float,
    ): PointSets {
        val forecastTemps = input.hours.map { it.temperature }
        val actualTemps =
            input.hours.map { it.actualTemperature ?: (it.temperature + effectiveDelta) }
        val expectedTemps = forecastTemps.map { it + effectiveDelta }
        val originalPoints = mutableListOf<Pair<Float, Float>>()
        val forecastPoints = mutableListOf<Pair<Float, Float>>()
        val expectedPoints = mutableListOf<Pair<Float, Float>>()
        input.hours.indices.forEach { index ->
            input.job?.ensureActive()
            val pointEpoch = input.hours[index].dateTime.toEpochSecond(ZoneOffset.UTC)
            val x = ((pointEpoch - input.minTimeEpoch) / SECONDS_PER_HOUR) * input.hourWidth
            originalPoints += input.toPoint(x, actualTemps[index])
            forecastPoints += input.toPoint(x, forecastTemps[index])
            expectedPoints += input.toPoint(x, expectedTemps[index])
        }
        input.onPointsResolved?.invoke(
            PointsDebug(originalPoints, forecastPoints, expectedPoints),
        )
        return PointSets(
            forecastTemps = forecastTemps,
            expectedTemps = expectedTemps,
            original = originalPoints,
            forecast = forecastPoints,
            expected = expectedPoints,
        )
    }

    private fun resolveTimeline(
        input: Input,
        originalPoints: List<Pair<Float, Float>>,
    ): Timeline {
        val fetchTime =
            input.observedAt?.let {
                Instant
                    .ofEpochMilli(it)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
            }
        val nowX =
            HourlyTimelineGeometry.computeNowX(
                input.hours,
                originalPoints,
                input.currentTime,
                input.hourWidth,
                { it.isCurrentHour },
                { it.dateTime },
            )
        val fetchDotX =
            fetchTime?.let {
                HourlyTimelineGeometry.computeXForTime(
                    it,
                    input.hours,
                    originalPoints,
                    input.hourWidth,
                ) { hour ->
                    hour.dateTime
                }
            }
        return Timeline(
            fetchTime = fetchTime,
            nowX = nowX,
            nowIndicatorVisible = nowX != null && nowX in 0f..input.widthPx.toFloat(),
            fetchDotX = fetchDotX,
        )
    }

    private fun resolveActualSeries(
        input: Input,
        originalPoints: List<Pair<Float, Float>>,
        fetchDotX: Float?,
        nowX: Float?,
    ): ActualSeries {
        val lastObservedActualIndex = input.hours.indexOfLast { it.isObservedActual }
        val lastObservedActualX =
            originalPoints.getOrNull(lastObservedActualIndex)?.first
        val lastActualIndex = input.hours.indexOfLast { it.isActual }
        val rawTransitionX =
            when {
                fetchDotX != null -> fetchDotX
                lastObservedActualX != null -> lastObservedActualX
                lastActualIndex >= 0 -> originalPoints[lastActualIndex].first
                else -> null
            }
        val transitionX =
            rawTransitionX?.let {
                listOfNotNull(it, nowX, fetchDotX).min()
            }
        val effectiveActualEndIndex =
            if (transitionX != null) {
                originalPoints
                    .indexOfLast { it.first <= transitionX + TRANSITION_CLIP_EXTRA_DP }
                    .takeIf { it >= 0 } ?: lastActualIndex
            } else {
                -1
            }
        val actualVisiblePoints =
            buildAnchoredActualPoints(
                originalPoints = originalPoints,
                transitionX = transitionX,
                fetchDotX = fetchDotX,
                lastObservedTemp = input.lastObservedTemp,
                minTemp = input.minTemp,
                tempRange = input.tempRange,
                graphTop = input.graphTop,
                graphHeight = input.graphHeight,
                job = input.job,
            )
        val (actualPath, _) =
            AndroidCurvePathBuilder.buildSmoothCurveAndFillPaths(
                actualVisiblePoints,
                input.graphBottom,
            )
        return ActualSeries(
            path = actualPath,
            visiblePoints = actualVisiblePoints,
            transitionX = transitionX,
            effectiveEndIndex = effectiveActualEndIndex,
        )
    }

    private fun Input.toPoint(
        x: Float,
        temperature: Float,
    ): Pair<Float, Float> =
        x to
            tempToY(
                temperature,
                graphTop,
                graphHeight,
                minTemp,
                tempRange,
            )

    fun interpolateYAtX(
        points: List<Pair<Float, Float>>,
        targetX: Float,
    ): Float {
        val exact =
            points.firstOrNull {
                abs(it.first - targetX) <= X_COORDINATE_MATCH_TOLERANCE
            }
        if (exact != null) return exact.second

        val afterIndex = points.indexOfFirst { it.first > targetX }
        return when {
            afterIndex <= 0 -> points.first().second
            else -> {
                val before = points[afterIndex - 1]
                val after = points[afterIndex]
                val span =
                    (after.first - before.first).coerceAtLeast(MIN_INTERPOLATION_SPAN)
                val fraction =
                    ((targetX - before.first) / span).coerceIn(0f, 1f)
                before.second + (after.second - before.second) * fraction
            }
        }
    }

    private fun buildAnchoredActualPoints(
        originalPoints: List<Pair<Float, Float>>,
        transitionX: Float?,
        fetchDotX: Float?,
        lastObservedTemp: Float?,
        minTemp: Float,
        tempRange: Float,
        graphTop: Float,
        graphHeight: Float,
        job: Job?,
    ): List<Pair<Float, Float>> {
        job?.ensureActive()
        if (transitionX == null || originalPoints.isEmpty()) return emptyList()

        val anchoredToFetchDot =
            fetchDotX != null &&
                abs(fetchDotX - transitionX) <= X_COORDINATE_MATCH_TOLERANCE &&
                lastObservedTemp != null
        val terminalY =
            if (anchoredToFetchDot) {
                tempToY(
                    lastObservedTemp,
                    graphTop,
                    graphHeight,
                    minTemp,
                    tempRange,
                )
            } else {
                interpolateYAtX(originalPoints, transitionX)
            }
        val visible =
            originalPoints
                .filter { it.first < transitionX - X_COORDINATE_MATCH_TOLERANCE }
                .toMutableList()
        visible += transitionX to terminalY
        return visible
    }

    private fun tempToY(
        temp: Float,
        graphTop: Float,
        graphHeight: Float,
        minTemp: Float,
        tempRange: Float,
    ): Float =
        TemperatureGraphStyle.tempToY(
            temp,
            graphTop,
            graphHeight,
            minTemp,
            tempRange,
        )
}

private data class PointSets(
    val forecastTemps: List<Float>,
    val expectedTemps: List<Float>,
    val original: List<Pair<Float, Float>>,
    val forecast: List<Pair<Float, Float>>,
    val expected: List<Pair<Float, Float>>,
)

private data class Timeline(
    val fetchTime: LocalDateTime?,
    val nowX: Float?,
    val nowIndicatorVisible: Boolean,
    val fetchDotX: Float?,
)

private data class ActualSeries(
    val path: Path,
    val visiblePoints: List<Pair<Float, Float>>,
    val transitionX: Float?,
    val effectiveEndIndex: Int,
)

data class TemperatureGraphSeriesGeometry(
    val forecastTemps: List<Float>,
    val expectedTemps: List<Float>,
    val originalPoints: List<Pair<Float, Float>>,
    val forecastPoints: List<Pair<Float, Float>>,
    val expectedPoints: List<Pair<Float, Float>>,
    val actualPath: Path,
    val actualVisiblePoints: List<Pair<Float, Float>>,
    val expectedPath: Path,
    val expectedFillPath: Path,
    val forecastSegmentPaths: List<AndroidCurvePathBuilder.IndexedCurvePath>,
    val nowX: Float?,
    val nowIndicatorVisible: Boolean,
    val fetchTime: LocalDateTime?,
    val fetchDotX: Float?,
    val transitionX: Float?,
    val effectiveActualEndIndex: Int,
)
