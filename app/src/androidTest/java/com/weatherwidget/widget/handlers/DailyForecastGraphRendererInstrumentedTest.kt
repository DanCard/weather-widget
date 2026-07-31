package com.weatherwidget.widget.handlers

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.DailyForecastGraphRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/** Real-Canvas and RemoteViews coverage for the typed daily rain-placement result. */
@RunWith(AndroidJUnit4::class)
class DailyForecastGraphRendererInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun equalDayAndNightTextReturnsTypedPlacementsAndUsesDayForHeaderCollision() {
        val date = LocalDate.of(2026, 7, 30)
        val sameText = "100000000000000000000000000000000000000000000000000000%"
        val headerDraws = mutableListOf<DailyForecastGraphRenderer.HeaderDrawnDebug>()

        val result =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days =
                    listOf(
                        DailyForecastGraphRenderer.DayData(
                            date = date,
                            label = "Today",
                            solidLineHigh = 100f,
                            solidLineLow = 72f,
                            isToday = true,
                            rainData =
                                DailyForecastGraphRenderer.RainLabelData(
                                    dailyPrecipProbability = 100,
                                    nighttimePrecipProbability = 100,
                                    dailyRainLabelText = sameText,
                                    nightRainLabelText = sameText,
                                ),
                        ),
                    ),
                widthPx = 500,
                heightPx = 400,
                headerData =
                    DailyForecastGraphRenderer.HeaderRenderData(
                        dateText = "Thu, Jul 30",
                    ),
                onHeaderDrawn = headerDraws::add,
                useCelsius = false,
            )

        assertEquals(500, result.bitmap.width)
        assertEquals(400, result.bitmap.height)
        assertEquals(
            setOf(
                DailyForecastGraphRenderer.RainLabelKind.DAY,
                DailyForecastGraphRenderer.RainLabelKind.NIGHT,
            ),
            result.rainLabelPlacements.map { it.kind }.toSet(),
        )
        assertTrue(headerDraws.single().dateSuppressedForRainOverlap)
    }

    @Test
    fun typedNightPlacementWiresEveryCoveredRemoteViewsGridCell() {
        val now = LocalDateTime.of(2026, 7, 30, 12, 0)
        val day =
            DailyForecastGraphRenderer.DayData(
                date = now.toLocalDate(),
                label = "Today",
                solidLineHigh = 70f,
                solidLineLow = 50f,
                rainData =
                    DailyForecastGraphRenderer.RainLabelData(
                        nighttimePrecipProbability = 65,
                        nightRainLabelText = "65%",
                    ),
            )
        val renderResult =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = listOf(day),
                widthPx = 500,
                heightPx = 400,
                useCelsius = false,
            )
        val nightPlacements =
            renderResult.rainLabelPlacements.filter {
                it.kind == DailyForecastGraphRenderer.RainLabelKind.NIGHT
            }
        val placement = nightPlacements.single()
        val coveredCells =
            NightRainGridMapper.computeNightRainGridCells(placement, 500, 400)
        assertTrue(coveredCells.isNotEmpty())

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        NightRainGridMapper.setupNightRainClickHandlers(
            context = context,
            views = views,
            appWidgetId = 730,
            now = now,
            days = listOf(day),
            lat = 37.42,
            lon = -122.08,
            displaySource = WeatherSource.NWS,
            bitmapWidthPx = 500,
            bitmapHeightPx = 400,
            nightLabelDraws = nightPlacements,
            buildClickIntent = { _, _, _, _, _, _, _, _, _, _, _ ->
                Intent("com.weatherwidget.TEST_NIGHT_RAIN")
            },
        )
        val applied = views.apply(context, FrameLayout(context))

        coveredCells.forEach { (row, column) ->
            val id =
                context.resources.getIdentifier(
                    "graph_night_rain_zone_r${row}_c$column",
                    "id",
                    context.packageName,
                )
            assertTrue(
                "Expected click listener for night-rain grid cell r$row c$column",
                applied.findViewById<View>(id).hasOnClickListeners(),
            )
        }
    }
}
