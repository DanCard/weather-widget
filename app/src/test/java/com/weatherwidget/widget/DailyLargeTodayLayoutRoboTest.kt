package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.handlers.DailyClickHandlerFactory
import com.weatherwidget.widget.WidgetActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyLargeTodayLayoutRoboTest {
    @Test
    fun `large overlay gives Today one and a quarter day widths and compact bars`() {
        val today = LocalDate.of(2026, 8, 4)
        val days =
            (0 until 9).map { index ->
                DailyForecastGraphRenderer.DayData(
                    date = today.plusDays(index.toLong() - 1),
                    label = if (index == 1) "Today" else "D$index",
                    solidLineHigh = 70f + index,
                    solidLineLow = 55f + index,
                    isToday = index == 1,
                    columnIndex = index,
                )
            }

        val layout =
            DailyGraphLayoutResolver.resolve(
                days = days,
                widthPx = 1_000,
                heightPx = 500,
                columns = 9,
                bitmapScale = 1f,
                density = 1f,
                useCelsius = false,
                todayColumnIndex = 1,
                useLargeTodayOverlay = true,
            )

        assertEquals(1_000f / 9.25f, layout.dayWidth, 0.01f)
        assertEquals(1_000f / 9.25f * 1.25f, layout.columnWidth(1), 0.01f)
        assertEquals(1_000f / 9.25f * 1.625f, layout.columnCenter(1), 0.01f)
        assertEquals(1_000f / 9.25f * 2.75f, layout.columnCenter(2), 0.01f)
        assertTrue(layout.dayWidth > 1_000f / 9.5f)
        assertTrue(layout.useCompactTodayBars)
        assertEquals(
            6f,
            DailyBarRenderer.todayTripleBarStrokeWidthPx(
                density = 1f,
                compact = layout.useCompactTodayBars,
            ),
            0.01f,
        )
    }

    @Test
    fun `large renderer draws delta and dominant temperature age without station text`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val today = LocalDate.of(2026, 8, 4)
        val days =
            (0 until 9).map { index ->
                DailyForecastGraphRenderer.DayData(
                    date = today.plusDays(index.toLong() - 1),
                    label = if (index == 1) "Today" else "D$index",
                    solidLineHigh = 70f + index,
                    solidLineLow = 55f + index,
                    isToday = index == 1,
                    columnIndex = index,
                )
            }

        val result =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 1_000,
                heightPx = 500,
                numColumns = 9,
                useLargeTodayOverlay = true,
                todayOverlayData =
                    DailyForecastGraphRenderer.TodayOverlayRenderData(
                        deltaValueText = "+3.2",
                        deltaCaptionText = "yest",
                        dominantTempText = "63.4°",
                        dominantAgeText = "15m",
                    ),
                useCelsius = false,
            )

        assertEquals(
            listOf("+3.2 yest", "63.4°", "15m"),
            result.todayOverlayPlacements.flatMap { it.text.lines() },
        )
        assertTrue(result.todayOverlayPlacements.none { "KNUQ" in it.text })
        assertEquals(3, result.todayOverlayPlacements.sumOf { it.text.lines().size })
        assertEquals(
            "All three primary rows must use one fitted font size",
            1,
            result.todayOverlayPlacements.map { it.mainTextSizePx }.distinct().size,
        )
        assertFalseOverlaps(result.todayOverlayPlacements)
    }

    @Test
    fun `Samsung ten by five geometry retains all three overlay rows`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val today = LocalDate.of(2026, 8, 4)
        val highs = listOf(88f, 89f, 77.2f, 86f, 83f, 86f, 87f, 86f, 82f)
        val lows = listOf(58.9f, 59.1f, 60.8f, 60f, 60f, 60f, 62f, 63f, 62f)
        val days =
            (0 until 9).map { index ->
                DailyForecastGraphRenderer.DayData(
                    date = today.plusDays(index.toLong() - 2),
                    label = if (index == 2) "Today" else "D$index",
                    solidLineHigh = highs[index],
                    solidLineLow = lows[index],
                    isToday = index == 2,
                    columnIndex = index,
                )
            }

        val result =
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 574,
                heightPx = 401,
                numColumns = 9,
                useLargeTodayOverlay = true,
                todayOverlayData =
                    DailyForecastGraphRenderer.TodayOverlayRenderData(
                        deltaValueText = "-3.1",
                        deltaCaptionText = "yest",
                        dominantTempText = "62.5°",
                        dominantAgeText = "5m",
                    ),
                useCelsius = false,
            )

        assertEquals(
            listOf("-3.1 yest", "62.5°", "5m"),
            result.todayOverlayPlacements.flatMap { it.text.lines() },
        )
    }

    @Test
    fun `inline yesterday caption is smaller than the delta value`() {
        assertTrue(TodayColumnOverlayRenderer.INLINE_CAPTION_TEXT_SCALE in 0f..<1f)
    }

    @Test
    fun `all overlay text uses opaque pure white`() {
        assertEquals(0xFFFFFFFF.toInt(), TodayColumnOverlayRenderer.MAIN_TEXT_COLOR)
        assertEquals(0xFFFFFFFF.toInt(), TodayColumnOverlayRenderer.INLINE_CAPTION_TEXT_COLOR)
    }

    @Test
    fun `overlay font size uses no horizontal condensing`() {
        val paint =
            TodayColumnOverlayRenderer.fittedPaint(
                color = android.graphics.Color.WHITE,
                labelScale = 1f,
                density = 1f,
            )

        // Width fitting is disabled: the fixed size stands and text may overflow narrow columns.
        assertEquals(1f, paint.textScaleX, 0f)
    }

    @Test
    fun `both launcher slots across wide Today column open Today`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val now = LocalDateTime.of(2026, 8, 4, 12, 0)
        val days =
            (0 until 9).map { index ->
                DailyForecastGraphRenderer.DayData(
                    date = now.toLocalDate().plusDays(index.toLong() - 1),
                    label = if (index == 1) "Today" else "D$index",
                    solidLineHigh = 70f,
                    solidLineLow = 55f,
                    isToday = index == 1,
                    columnIndex = index,
                )
            }
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        DailyClickHandlerFactory.setupGraphDayClickHandlers(
            context = context,
            views = views,
            appWidgetId = 42,
            now = now,
            days = days,
            lat = 37.4,
            lon = -122.1,
            displaySource = WeatherSource.NWS,
            numColumns = 9,
            useLargeTodayOverlay = true,
        )
        val applied = views.apply(context, FrameLayout(context))

        val firstTodaySlot = applied.findViewById<View>(R.id.graph_day2_zone)
        val secondTodaySlot = applied.findViewById<View>(R.id.graph_day3_zone)
        firstTodaySlot.performClick()
        secondTodaySlot.performClick()

        val broadcasts = shadowOf(context).broadcastIntents.takeLast(2)
        assertEquals(2, broadcasts.size)
        assertTrue(broadcasts.all { it.action == WidgetActions.ACTION_DAY_CLICK })
        assertTrue(broadcasts.all { it.getStringExtra("date") == now.toLocalDate().toString() })
    }

    private fun assertFalseOverlaps(
        placements: List<DailyForecastGraphRenderer.TodayOverlayPlacementDebug>,
    ) {
        placements.forEachIndexed { index, first ->
            placements.drop(index + 1).forEach { second ->
                val overlaps =
                    first.left < second.right && second.left < first.right &&
                        first.top < second.bottom && second.top < first.bottom
                assertTrue("overlay placements overlap: $first and $second", !overlaps)
            }
        }
    }
}
