package com.weatherwidget.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.Window

/**
 * Restores a window from minimized/iconified state and surfaces it to the front, requesting focus.
 */
internal fun bringWindowToFront(
    window: Window,
    windowState: WindowState? = null,
) {
    if (windowState?.isMinimized == true) {
        windowState.isMinimized = false
    }
    if (window is Frame) {
        if ((window.extendedState and Frame.ICONIFIED) != 0) {
            window.extendedState = Frame.NORMAL
        }
    }
    window.toFront()
    window.requestFocus()
}

/**
 * Compose effect that ensures a window is de-minimized, brought to front, and focused
 * both upon initial presentation and whenever [showRequestId] increments.
 */
@Composable
internal fun BringToFrontOnShow(
    window: Window,
    windowState: WindowState? = null,
    showRequestId: Int,
) {
    LaunchedEffect(showRequestId) {
        bringWindowToFront(window, windowState)
    }
}
