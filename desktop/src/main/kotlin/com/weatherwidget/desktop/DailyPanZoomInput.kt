package com.weatherwidget.desktop

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Shared mouse input for the desktop daily forecast surface, the daily counterpart to
 * [hourlyPanZoomInput]:
 *   - **Scroll wheel** zooms by day count: scroll down (dy > 0, same sign as the hourly zoom-out)
 *     prepends one history day, scroll up trims one — [onZoomScroll] gets ±1.
 *   - **Horizontal click-drag** pans in whole-day snap steps. Pixels accumulate; each full column
 *     width (`size.width / columnCount`) dragged emits one [onPanDays] step *during* the drag, so the
 *     view snaps column-by-column with real data (no smooth glide — the daily graph renders no
 *     off-screen columns to slide in). Each step writes config, exactly like a nav-arrow click.
 *
 * Coexists with the graph's own `detectTapGestures` (a separate `pointerInput`): a horizontal drag
 * consumes its moves so the tap is cancelled, while a plain tap still falls through to the tap handler.
 * Keyed on [columnCount] only, so day-offset commits during a pan do not tear down the in-flight drag.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.dailyPanZoomInput(
    columnCount: Int,
    onPanDays: (deltaDays: Int) -> Unit,
    onZoomScroll: (deltaDays: Int) -> Unit,
): Modifier {
    val currentOnPanDays by rememberUpdatedState(onPanDays)
    val currentOnZoomScroll by rememberUpdatedState(onZoomScroll)
    return this
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            when {
                dy > 0f -> currentOnZoomScroll(1)   // zoom out: add a history day
                dy < 0f -> currentOnZoomScroll(-1)  // zoom in: trim a history day
            }
        }
        .pointerInput(columnCount) {
            var accumPx = 0f
            detectHorizontalDragGestures(
                onDragStart = { accumPx = 0f },
                onDragEnd = { accumPx = 0f },
                onDragCancel = { accumPx = 0f },
            ) { change, dragAmount ->
                change.consume()
                val dayWidth = size.width.toFloat() / columnCount.coerceAtLeast(1)
                accumPx += dragAmount
                val steps = DesktopGraphUtils.panDeltaDays(accumPx, dayWidth)
                if (steps != 0) {
                    accumPx += steps * dayWidth // remove the consumed whole columns
                    currentOnPanDays(steps)
                }
            }
        }
}
