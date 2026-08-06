package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyForecastHeaderDeltaLabelTest {

    @Test
    fun `label drawn when date still fits with it`() {
        assertTrue(
            DailyForecastHeaderRenderer.shouldDrawDeltaLabel(
                hasDateText = true,
                dateFitsWithLabel = true,
                dateFitsWithoutLabel = true,
                leftWithLabelRight = 100f,
                apiLeft = 50f, // api boundary violated, but date placement already decided it fits
                gapPx = 10f,
            )
        )
    }

    @Test
    fun `label hidden when it would crowd out the date`() {
        assertFalse(
            DailyForecastHeaderRenderer.shouldDrawDeltaLabel(
                hasDateText = true,
                dateFitsWithLabel = false,
                dateFitsWithoutLabel = true,
                leftWithLabelRight = 100f,
                apiLeft = 500f,
                gapPx = 10f,
            )
        )
    }

    @Test
    fun `label drawn when date fits neither way and cluster clears api label`() {
        assertTrue(
            DailyForecastHeaderRenderer.shouldDrawDeltaLabel(
                hasDateText = true,
                dateFitsWithLabel = false,
                dateFitsWithoutLabel = false,
                leftWithLabelRight = 100f,
                apiLeft = 200f,
                gapPx = 10f,
            )
        )
    }

    @Test
    fun `label hidden when date fits neither way and cluster hits api label`() {
        assertFalse(
            DailyForecastHeaderRenderer.shouldDrawDeltaLabel(
                hasDateText = true,
                dateFitsWithLabel = false,
                dateFitsWithoutLabel = false,
                leftWithLabelRight = 195f,
                apiLeft = 200f,
                gapPx = 10f,
            )
        )
    }

    @Test
    fun `label drawn without date when cluster clears api label`() {
        assertTrue(
            DailyForecastHeaderRenderer.shouldDrawDeltaLabel(
                hasDateText = false,
                dateFitsWithLabel = false,
                dateFitsWithoutLabel = false,
                leftWithLabelRight = 100f,
                apiLeft = 200f,
                gapPx = 10f,
            )
        )
    }

    @Test
    fun `label hidden without date when cluster hits api label`() {
        assertFalse(
            DailyForecastHeaderRenderer.shouldDrawDeltaLabel(
                hasDateText = false,
                dateFitsWithLabel = false,
                dateFitsWithoutLabel = false,
                leftWithLabelRight = 200f,
                apiLeft = 200f,
                gapPx = 10f,
            )
        )
    }
}
