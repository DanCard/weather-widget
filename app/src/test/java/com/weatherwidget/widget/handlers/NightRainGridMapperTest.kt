package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.DailyForecastGraphRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class NightRainGridMapperTest {
    @Test
    fun `typed night placement retains horizontal click zone geometry`() {
        val placement =
            DailyForecastGraphRenderer.DailyRainLabelPlacement(
                date = LocalDate.of(2026, 7, 30),
                text = "65%",
                placement = "NIGHT_SHIFTED_LEFT",
                kind = DailyForecastGraphRenderer.RainLabelKind.NIGHT,
                centerX = 125f,
                leftX = 100f,
                rightX = 150f,
                baselineY = 120f,
                topY = 100f,
                bottomY = 125f,
            )

        val cells =
            NightRainGridMapper.computeNightRainGridCells(
                labelDraw = placement,
                bitmapWidthPx = 400,
                bitmapHeightPx = 200,
            )

        assertEquals(NightRainGridMapper.GRID_ROWS * 3, cells.size)
        assertEquals(setOf(5, 6, 7), cells.map { it.second }.toSet())
        assertEquals((0 until NightRainGridMapper.GRID_ROWS).toSet(), cells.map { it.first }.toSet())
    }

    @Test
    fun `invalid typed placement produces no click zones`() {
        val placement =
            DailyForecastGraphRenderer.DailyRainLabelPlacement(
                date = LocalDate.of(2026, 7, 30),
                text = "65%",
                placement = "NIGHT_CENTERED",
                kind = DailyForecastGraphRenderer.RainLabelKind.NIGHT,
                centerX = Float.NaN,
                baselineY = 100f,
            )

        assertTrue(
            NightRainGridMapper.computeNightRainGridCells(placement, 400, 200).isEmpty(),
        )
    }
}
