package com.weatherwidget.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopWindowSanitizerTest {

    @Test
    fun `saved 4K coordinates off-screen on 720p reset position to center and clamp height`() {
        // Real-world scenario from bug report:
        // config saved on 4K: (X=1251, Y=691, W=500, H=708)
        // active screen: 1280 x 720
        val sanitized = DesktopWindowSanitizer.sanitizeWindowGeometry(
            savedX = 1251f,
            savedY = 691f,
            savedWidth = 500f,
            savedHeight = 708f,
            defaultWidth = 500.dp,
            defaultHeight = 700.dp,
            defaultAlignment = Alignment.Center,
            screenWidth = 1280,
            screenHeight = 720,
        )

        // Must reset to Center because Y=691 places the title bar only 29px from the bottom,
        // and X=1251 places the window 471px off-screen to the right.
        assertEquals(WindowPosition(Alignment.Center), sanitized.position)
        // Height must be clamped so it doesn't exceed screen height minus margins
        assertTrue(sanitized.height <= 660.dp)
        assertEquals(500.dp, sanitized.width)
    }

    @Test
    fun `valid on-screen coordinates on 720p are preserved`() {
        val sanitized = DesktopWindowSanitizer.sanitizeWindowGeometry(
            savedX = 150f,
            savedY = 100f,
            savedWidth = 400f,
            savedHeight = 500f,
            defaultWidth = 400.dp,
            defaultHeight = 500.dp,
            defaultAlignment = Alignment.Center,
            screenWidth = 1280,
            screenHeight = 720,
        )

        assertEquals(WindowPosition(150.dp, 100.dp), sanitized.position)
        assertEquals(400.dp, sanitized.width)
        assertEquals(500.dp, sanitized.height)
    }

    @Test
    fun `partially clipped coordinates are gently clamped to remain visible`() {
        // Window extends slightly beyond right edge (X=800, W=500 -> right edge at 1300 > 1280)
        val sanitized = DesktopWindowSanitizer.sanitizeWindowGeometry(
            savedX = 800f,
            savedY = 100f,
            savedWidth = 500f,
            savedHeight = 400f,
            defaultWidth = 500.dp,
            defaultHeight = 400.dp,
            defaultAlignment = Alignment.Center,
            screenWidth = 1280,
            screenHeight = 720,
        )

        val pos = sanitized.position as WindowPosition.Absolute
        // Clamped so X + width <= 1280 - margin
        assertTrue(pos.x <= 770.dp)
        assertTrue(pos.x >= 10.dp)
    }

    @Test
    fun `null coordinates default to specified alignment and default dimensions`() {
        val sanitized = DesktopWindowSanitizer.sanitizeWindowGeometry(
            savedX = null,
            savedY = null,
            savedWidth = null,
            savedHeight = null,
            defaultWidth = 380.dp,
            defaultHeight = 320.dp,
            defaultAlignment = Alignment.TopEnd,
            screenWidth = 1280,
            screenHeight = 720,
        )

        assertEquals(WindowPosition(Alignment.TopEnd), sanitized.position)
        assertEquals(380.dp, sanitized.width)
        assertEquals(320.dp, sanitized.height)
    }

    @Test
    fun `negative Y coordinate places title bar off top and resets to center`() {
        val sanitized = DesktopWindowSanitizer.sanitizeWindowGeometry(
            savedX = 100f,
            savedY = -50f,
            savedWidth = 500f,
            savedHeight = 400f,
            defaultWidth = 500.dp,
            defaultHeight = 400.dp,
            defaultAlignment = Alignment.Center,
            screenWidth = 1280,
            screenHeight = 720,
        )

        assertEquals(WindowPosition(Alignment.Center), sanitized.position)
    }

    @Test
    fun `oversized window size clamps to available screen dimensions`() {
        val sanitized = DesktopWindowSanitizer.sanitizeWindowGeometry(
            savedX = null,
            savedY = null,
            savedWidth = 2000f,
            savedHeight = 1500f,
            defaultWidth = 500.dp,
            defaultHeight = 700.dp,
            defaultAlignment = Alignment.Center,
            screenWidth = 1280,
            screenHeight = 720,
        )

        assertTrue(sanitized.width <= 1240.dp)
        assertTrue(sanitized.height <= 660.dp)
    }
}
