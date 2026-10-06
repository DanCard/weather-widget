package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class FailureBannerStageTest {
    @Test
    fun `full for eight seconds, tiny until twenty-four, then faded`() {
        assertEquals(FailureBannerStage.FULL, FailureBannerStage.at(0L))
        assertEquals(FailureBannerStage.FULL, FailureBannerStage.at(7_999L))
        assertEquals(FailureBannerStage.TINY, FailureBannerStage.at(8_000L))
        assertEquals(FailureBannerStage.TINY, FailureBannerStage.at(23_999L))
        assertEquals(FailureBannerStage.FADED, FailureBannerStage.at(24_000L))
        assertEquals(FailureBannerStage.FADED, FailureBannerStage.at(86_400_000L))
    }

    @Test
    fun `a clock stepped back shows the full banner`() {
        assertEquals(FailureBannerStage.FULL, FailureBannerStage.at(-5_000L))
    }
}
