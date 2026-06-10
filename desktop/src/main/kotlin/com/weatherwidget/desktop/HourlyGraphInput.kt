package com.weatherwidget.desktop

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
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
