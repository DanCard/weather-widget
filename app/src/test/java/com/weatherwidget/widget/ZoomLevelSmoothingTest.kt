package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ZoomLevelSmoothingTest {

    @Test
    fun `wide zoom uses three smoothing iterations`() {
        assertEquals(3, ZoomStage.WIDE.window().smoothIterations)
    }

    @Test
    fun `narrow zoom uses one smoothing iteration`() {
        assertEquals(1, ZoomStage.NARROW.window().smoothIterations)
    }
}
