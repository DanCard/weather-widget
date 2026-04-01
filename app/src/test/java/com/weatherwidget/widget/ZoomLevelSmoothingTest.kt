package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomLevelSmoothingTest {

    @Test
    fun `wide zoom uses zero smoothing iterations`() {
        assertEquals(0, ZoomLevel.WIDE.smoothIterations)
    }

    @Test
    fun `narrow zoom remains at one smoothing iteration`() {
        assertEquals(1, ZoomLevel.NARROW.smoothIterations)
    }
}
