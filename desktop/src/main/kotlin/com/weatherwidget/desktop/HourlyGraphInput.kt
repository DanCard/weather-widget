package com.weatherwidget.desktop

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.HourlyForecast
import kotlin.math.roundToInt

/**
 * Shared mouse input for the desktop hourly graphs (Temperature, Precipitation, CloudCover):
 *   - **Scroll wheel** zooms continuously, re-centered on the cursor.
 *   - **Horizontal click-drag** pans through time. The live pan accumulates into [dragHours] (read by
 *     the renderer for a smooth sub-hour slide) and is persisted exactly once, on release, via
 *     [onPanCommit] — important because committing writes config to disk and pings the daemon.
 *
 * Coexists with each graph's own `detectTapGestures` (a separate `pointerInput`): a horizontal drag
 * consumes its moves so the tap is cancelled, while a plain tap still falls through to the tap handler.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.hourlyPanZoomInput(
    start: Long,
    cutoff: Long,
    nowMs: Long,
    spanHours: Int,
    dragHours: MutableState<Float>,
    onZoomScroll: (deltaZoom: Float, centerOffset: Int) -> Unit,
    onPanCommit: (deltaHours: Int) -> Unit,
): Modifier {
    // Keep the gesture callbacks fresh even though the drag pointerInput is keyed only on spanHours
    // (so it is NOT torn down mid-pan when start/cutoff shift at each hour boundary).
    val currentOnZoomScroll by rememberUpdatedState(onZoomScroll)
    val currentOnPanCommit by rememberUpdatedState(onPanCommit)
    return this
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (dy != 0f) {
                val cursorX = event.changes.first().position.x
                val cursorTimeMs = start + (cursorX / size.width.toFloat()) * (cutoff - start)
                val cursorOffset = ((cursorTimeMs - nowMs) / 3_600_000f).roundToInt()
                currentOnZoomScroll(dy * DesktopGraphUtils.ZOOM_SENSITIVITY, cursorOffset)
            }
        }
        .pointerInput(spanHours) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val committed = dragHours.value.roundToInt()
                    dragHours.value = 0f
                    if (committed != 0) currentOnPanCommit(committed)
                },
                onDragCancel = { dragHours.value = 0f },
            ) { change, dragAmount ->
                change.consume()
                dragHours.value += DesktopGraphUtils.panDeltaHours(dragAmount, size.width.toFloat(), spanHours)
            }
        }
}

/**
 * The cloud-cover and precipitation graphs share an identical Canvas input chain: [hourlyPanZoomInput]
 * plus a footer-tap handler that, when the bottom ~44dp strip is clicked, maps the clicked hour's
 * weather icon to its "home" view and fires [onViewModeChange]. (The temperature graph's tap differs —
 * it toggles zoom — so it keeps its own chain.)
 */
@Composable
fun Modifier.hourlyGraphFooterTapInput(
    start: Long,
    cutoff: Long,
    nowMs: Long,
    spanHours: Int,
    dragHours: MutableState<Float>,
    points: List<HourlyForecast>,
    zoomFactor: Float,
    scale: Float,
    onViewModeChange: (String) -> Unit,
    onZoomScroll: (deltaZoom: Float, centerOffset: Int) -> Unit,
    onPan: (deltaHours: Int) -> Unit,
): Modifier = this
    .hourlyPanZoomInput(start, cutoff, nowMs, spanHours, dragHours, onZoomScroll, onPan)
    .pointerInput(points, zoomFactor, scale) {
        detectTapGestures { offset ->
            if (offset.y >= size.height - 44.dp.toPx() * scale) {
                val stepWidth = size.width / (points.size - 1).coerceAtLeast(1)
                val index = (offset.x / stepWidth).roundToInt().coerceIn(0, points.lastIndex)
                val clickedPoint = points[index]
                val iconRes = WeatherIcon.getIconResource(clickedPoint.condition)
                val targetView = WeatherIcon.resolveIconHome(iconRes)
                onViewModeChange(targetView)
            }
        }
    }

/** Computed window + point/painter state shared by the desktop hourly graphs. */
internal data class HourlyGraphSetup(
    val now: Long,
    val dragHours: MutableState<Float>,
    val totalSpanHours: Int,
    val start: Long,
    val cutoff: Long,
    val points: List<HourlyForecast>,
    val painters: List<Painter>,
    val smoothIterations: Int,
)

/**
 * Builds the pre-Canvas setup the cloud-cover and precipitation graphs compute identically: the time
 * window (centered on now + drag + [centerOffsetHours], spanned by [zoomFactor]), the points filtered
 * to that window (with an ifEmpty fallback), one weather-icon painter per point, and the smoothing
 * iteration count. Returns null when there are fewer than two points to plot — callers do `?: return`.
 *
 * The watermark painter stays at each call site since it differs per graph (cloudy vs rain drawable),
 * as does `rememberTextMeasurer()`.
 */
@Composable
internal fun rememberHourlyGraphSetup(
    hourly: List<HourlyForecast>,
    centerOffsetHours: Int,
    zoomFactor: Float,
): HourlyGraphSetup? {
    val now = System.currentTimeMillis()
    val dragHours = remember { mutableStateOf(0f) }
    val center = now + (centerOffsetHours + dragHours.value.roundToInt()) * 3_600_000L

    val backHours = DesktopGraphUtils.backHoursFor(zoomFactor)
    val forwardHours = DesktopGraphUtils.forwardHoursFor(zoomFactor)
    val totalSpanHours = backHours + forwardHours

    val start = center - backHours * 3_600_000L
    val cutoff = center + forwardHours * 3_600_000L

    val points = remember(hourly, start, cutoff) {
        hourly.filter { it.dateTime in (start - 3_600_000L)..cutoff }
            .sortedBy { it.dateTime }
            .ifEmpty { hourly.sortedBy { it.dateTime }.take(backHours + forwardHours + 1) }
    }

    val painters: List<Painter> = points.map { painterResource(WeatherIcon.getIconResource(it.condition)) }
    val smoothIterations = DesktopGraphUtils.smoothIterationsFor(totalSpanHours)

    if (points.size < 2) return null

    return HourlyGraphSetup(now, dragHours, totalSpanHours, start, cutoff, points, painters, smoothIterations)
}
