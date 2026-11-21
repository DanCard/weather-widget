package com.weatherwidget.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState

data class SanitizedWindowGeometry(
    val position: WindowPosition,
    val width: Dp,
    val height: Dp,
    /** Why [position] was chosen: no-saved, off-screen, restored or clamped (WINDOW_GEOMETRY log). */
    val decision: String = "restored",
)

private const val TAG = "DesktopWindowSanitizer"

object DesktopWindowSanitizer {

    fun sanitizeWindowGeometry(
        savedX: Float?,
        savedY: Float?,
        savedWidth: Float?,
        savedHeight: Float?,
        defaultWidth: Dp,
        defaultHeight: Dp,
        defaultAlignment: Alignment = Alignment.Center,
        screenWidth: Int,
        screenHeight: Int,
        minWidth: Dp = 320.dp,
        minHeight: Dp = 240.dp,
    ): SanitizedWindowGeometry {
        val maxAvailableWidth = (screenWidth - 40).coerceAtLeast(minWidth.value.toInt()).dp
        val maxAvailableHeight = (screenHeight - 60).coerceAtLeast(minHeight.value.toInt()).dp

        val width = (savedWidth?.dp ?: defaultWidth).coerceIn(minWidth, maxAvailableWidth)
        val height = (savedHeight?.dp ?: defaultHeight).coerceIn(minHeight, maxAvailableHeight)

        if (savedX == null || savedY == null) {
            return SanitizedWindowGeometry(WindowPosition(defaultAlignment), width, height, "default:no-saved")
        }

        // Off-screen checks for coordinate sanity on the current display:
        // 1. Right edge check: starts beyond screen or leaves < 100px visible on the right
        // 2. Left edge check: completely off the left edge (X < -width + 100)
        // 3. Bottom edge check: title bar below the screen (Y + 50 > screenHeight, e.g. Y=749 on 720p)
        // 4. Top edge check: title bar above top of screen (Y < 0)
        val isOffScreen = (savedX + 100 > screenWidth) ||
            (savedX < -width.value + 100) ||
            (savedY + 50 > screenHeight) ||
            (savedY < 0)

        if (isOffScreen) {
            return SanitizedWindowGeometry(WindowPosition(defaultAlignment), width, height, "default:off-screen")
        }

        // Gently clamp to stay comfortably within the visible screen area
        val maxX = (screenWidth - width.value - 10).coerceAtLeast(10f)
        val maxY = (screenHeight - height.value - 20).coerceAtLeast(10f)
        val clampedX = savedX.coerceIn(10f, maxX)
        val clampedY = savedY.coerceIn(10f, maxY)

        val decision = if (clampedX == savedX && clampedY == savedY) "restored" else "clamped"
        return SanitizedWindowGeometry(WindowPosition(clampedX.dp, clampedY.dp), width, height, decision)
    }

    /**
     * Resolves active screen dimensions. In GUI Compose Desktop, queries AWT Toolkit screen size,
     * falling back to DisplayResolutionDetector.
     */
    fun activeScreenDimensions(): DisplayDimensions = activeScreenDimensionsWithSource().first

    /** [activeScreenDimensions] plus where they came from ("awt", "awt-empty->detector", "awt-failed->detector"). */
    fun activeScreenDimensionsWithSource(): Pair<DisplayDimensions, String> {
        return try {
            val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
            if (screenSize.width > 0 && screenSize.height > 0) {
                DisplayDimensions(screenSize.width, screenSize.height) to "awt"
            } else {
                DisplayResolutionDetector.currentScreenDimensions() to "awt-empty->detector"
            }
        } catch (e: Exception) {
            com.weatherwidget.shared.util.Log.e(TAG, "AWT screen size failed; using DisplayResolutionDetector", e)
            DisplayResolutionDetector.currentScreenDimensions() to "awt-failed->detector"
        }
    }
}

/**
 * Creates and remembers a [WindowState] whose size and position are sanitized against the current
 * monitor resolution, preventing windows saved on higher resolutions (e.g. 4K) from opening
 * off-screen or oversized on lower resolutions (e.g. 720p).
 */
@Composable
fun rememberSanitizedWindowState(
    savedX: Float?,
    savedY: Float?,
    savedWidth: Float?,
    savedHeight: Float?,
    defaultWidth: Dp,
    defaultHeight: Dp,
    defaultAlignment: Alignment = Alignment.Center,
    /** Called once per resolution with a WINDOW_GEOMETRY description (the main window logs it). */
    onResolved: ((String) -> Unit)? = null,
): WindowState {
    val (dimensions, dimensionsSource) = remember { DesktopWindowSanitizer.activeScreenDimensionsWithSource() }
    // Once per window: rememberWindowState only reads its first position and size, so re-running on
    // each saved move only re-logged a startup that never happened.
    val sanitized = remember {
        DesktopWindowSanitizer.sanitizeWindowGeometry(
            savedX = savedX,
            savedY = savedY,
            savedWidth = savedWidth,
            savedHeight = savedHeight,
            defaultWidth = defaultWidth,
            defaultHeight = defaultHeight,
            defaultAlignment = defaultAlignment,
            screenWidth = dimensions.width,
            screenHeight = dimensions.height,
        ).also { result ->
            onResolved?.invoke(
                "startup saved=$savedX,$savedY size=${savedWidth}x$savedHeight " +
                    "screen=${dimensions.width}x${dimensions.height} via=$dimensionsSource " +
                    "decision=${result.decision} position=${result.position}",
            )
        }
    }
    return rememberWindowState(
        position = sanitized.position,
        width = sanitized.width,
        height = sanitized.height,
    )
}
