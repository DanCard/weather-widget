package com.weatherwidget.desktop

import androidx.compose.ui.window.WindowState
import com.weatherwidget.test.category.ShortDuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.awt.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopWindowFocusTest {

    @Test
    fun `bringWindowToFront resets minimized windowState to false`() {
        val windowState = WindowState(isMinimized = true)
        val mockFrame = mockk<Frame>(relaxed = true)
        every { mockFrame.extendedState } returns Frame.NORMAL

        bringWindowToFront(mockFrame, windowState)

        assertFalse("windowState.isMinimized should be reset to false", windowState.isMinimized)
        verify(exactly = 1) { mockFrame.toFront() }
        verify(exactly = 1) { mockFrame.requestFocus() }
    }

    @Test
    fun `bringWindowToFront restores iconified frame state to normal`() {
        val mockFrame = mockk<Frame>(relaxed = true)
        every { mockFrame.extendedState } returns Frame.ICONIFIED

        bringWindowToFront(mockFrame, null)

        verify(exactly = 1) { mockFrame.extendedState = Frame.NORMAL }
        verify(exactly = 1) { mockFrame.toFront() }
        verify(exactly = 1) { mockFrame.requestFocus() }
    }

    @Test
    fun `bringWindowToFront handles normal window and invokes toFront and requestFocus`() {
        val mockFrame = mockk<Frame>(relaxed = true)
        every { mockFrame.extendedState } returns Frame.NORMAL

        bringWindowToFront(mockFrame, WindowState(isMinimized = false))

        verify(exactly = 1) { mockFrame.toFront() }
        verify(exactly = 1) { mockFrame.requestFocus() }
    }
}
