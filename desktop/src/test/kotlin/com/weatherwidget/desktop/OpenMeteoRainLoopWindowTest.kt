package com.weatherwidget.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import com.weatherwidget.test.category.ShortDuration

@Category(ShortDuration::class)
class OpenMeteoRainLoopWindowTest {
    @Test
    fun `pin label joins a bare house number with its street`() {
        assertEquals("860 Avery Drive", shortPlace("860, Avery Drive, Mountain View, Santa Clara County, California, 94043, United States"))
        assertEquals("Kyiv", shortPlace("Kyiv, Ukraine"))
        assertNull(shortPlace(""))
    }
}
