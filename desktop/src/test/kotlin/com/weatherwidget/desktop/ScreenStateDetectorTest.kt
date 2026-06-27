package com.weatherwidget.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenStateDetectorTest {

    private val xsetOn = """
        DPMS (Display Power Management Signaling):
          Standby: 600    Suspend: 600    Off: 600
          DPMS is Enabled
          Monitor is On
    """.trimIndent()

    private fun xsetMonitor(state: String) = "  DPMS is Enabled\n  Monitor is $state"

    @Test
    fun `parseXsetMonitorState true when monitor on`() {
        assertEquals(true, ScreenStateDetector.parseXsetMonitorState(xsetOn))
    }

    @Test
    fun `parseXsetMonitorState false when off standby or suspend`() {
        assertEquals(false, ScreenStateDetector.parseXsetMonitorState(xsetMonitor("Off")))
        assertEquals(false, ScreenStateDetector.parseXsetMonitorState(xsetMonitor("in Standby")))
        assertEquals(false, ScreenStateDetector.parseXsetMonitorState(xsetMonitor("in Suspend")))
    }

    @Test
    fun `parseXsetMonitorState null when no monitor line or null input`() {
        assertNull(ScreenStateDetector.parseXsetMonitorState("DPMS is Disabled"))
        assertNull(ScreenStateDetector.parseXsetMonitorState(null))
    }

    @Test
    fun `parseLoginctlLocked on when not locked, off when locked`() {
        assertEquals(true, ScreenStateDetector.parseLoginctlLocked("LockedHint=no"))
        assertEquals(false, ScreenStateDetector.parseLoginctlLocked("LockedHint=yes"))
    }

    @Test
    fun `parseLoginctlLocked null when property absent or null`() {
        assertNull(ScreenStateDetector.parseLoginctlLocked("IdleHint=no"))
        assertNull(ScreenStateDetector.parseLoginctlLocked(null))
    }
}
