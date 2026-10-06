package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class DesktopSourceSwitchFeedbackTest {
    @Test
    fun `nothing cached is not drawable`() {
        assertFalse(DesktopSourceSwitchFeedback.hasDrawableCache(null, LocalDate.now()))
    }

    @Test
    fun `messages name the source`() {
        assertEquals("Getting weather from Google Weather…", DesktopSourceSwitchFeedback.fetchingMessage(WeatherSource.GOOGLE_WEATHER))
    }
}
