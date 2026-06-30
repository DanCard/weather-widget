package com.weatherwidget.shared.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostLineGateTest {

    private val width = 567f

    @Test
    fun allowsWhenNowIndicatorVisible() {
        assertTrue(GhostLineGate.shouldProcess(fetchDotX = -3000f, graphWidthPx = width, spanHours = 24, nowIndicatorVisible = true))
    }

    @Test
    fun allowsWhenFetchDotOnScreen() {
        assertTrue(GhostLineGate.shouldProcess(fetchDotX = 200f, graphWidthPx = width, spanHours = 24, nowIndicatorVisible = false))
    }

    @Test
    fun allowsNarrowOffLeftNearTermScroll() {
        assertTrue(GhostLineGate.shouldProcess(fetchDotX = -200f, graphWidthPx = width, spanHours = 4, nowIndicatorVisible = false))
    }

    @Test
    fun rejectsFarFutureWideViewWithFetchFarOffLeft() {
        assertFalse(GhostLineGate.shouldProcess(fetchDotX = -3453f, graphWidthPx = width, spanHours = 24, nowIndicatorVisible = false))
    }

    @Test
    fun rejectsWhenFetchDotNull() {
        assertFalse(GhostLineGate.shouldProcess(fetchDotX = null, graphWidthPx = width, spanHours = 4, nowIndicatorVisible = true))
    }

    @Test
    fun rejectsWideViewWithFetchBarelyOffLeft() {
        assertFalse(GhostLineGate.shouldProcess(fetchDotX = -100f, graphWidthPx = width, spanHours = 24, nowIndicatorVisible = false))
    }

    @Test
    fun rejectsFarFutureNarrowViewEvenWhenFetchWithinOneViewport() {
        assertFalse(
            GhostLineGate.shouldProcess(
                fetchDotX = -200f,
                graphWidthPx = width,
                spanHours = 7,
                nowIndicatorVisible = false,
                hoursFromNowToWindowStart = 146,
            ),
        )
    }
}