package com.weatherwidget.widget

import com.weatherwidget.shared.graph.DailyBarWidths
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.time.LocalDate


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyForecastGraphRendererSizingTest {

    @Test
    fun `day label width scale shrinks only slightly on tight columns`() {
        val scale = DailyGraphLayoutResolver.computeDayLabelWidthScale(dayWidthDp = 60f)

        assertEquals(0.96f, scale, 0.0001f)
    }

    @Test
    fun `day label width scale stays at baseline on standard columns`() {
        val scale = DailyGraphLayoutResolver.computeDayLabelWidthScale(dayWidthDp = 70f)

        assertEquals(1.0f, scale, 0.0001f)
    }

    @Test
    fun `forecast temperature label size uses larger daily baseline`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val sizePx = DailyGraphLayoutResolver.dailyForecastTempLabelSizePx(context.resources.displayMetrics.density)

        assertEquals(24f * context.resources.displayMetrics.density, sizePx, 0.01f)
    }

    @Test
    fun `forecast temperature label size compensates for bitmap downscale`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val sizePx = DailyGraphLayoutResolver.dailyForecastTempLabelSizePx(context.resources.displayMetrics.density, bitmapScale = 0.34f)

        assertEquals(12f * context.resources.displayMetrics.density, sizePx, 0.01f)
    }

    @Test
    fun `forecast temperature label size uses smaller scale for short widgets`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val sizePx = DailyGraphLayoutResolver.dailyForecastTempLabelSizePx(context.resources.displayMetrics.density, heightScaleFactor = 0.92f)

        assertEquals(22.08f * context.resources.displayMetrics.density, sizePx, 0.01f)
    }

    @Test
    fun `forecast temperature label size stays at baseline for tall widgets`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val sizePx = DailyGraphLayoutResolver.dailyForecastTempLabelSizePx(context.resources.displayMetrics.density, heightScaleFactor = 1.0f)

        assertEquals(24f * context.resources.displayMetrics.density, sizePx, 0.01f)
    }

    @Test
    fun `daily bar stroke uses wider baseline and bitmap scale floor`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val density = context.resources.displayMetrics.density
        val fullScale = DailyBarWidths.resolve(
            DailyBarRenderer.barPxPerDp(density), dayWidthPx = 1_000f, todayWidened = false,
        ).forecastPx
        val downscaled = DailyBarWidths.resolve(
            DailyBarRenderer.barPxPerDp(density, bitmapScale = 0.34f), dayWidthPx = 1_000f, todayWidened = false,
        ).forecastPx

        assertEquals(9f * context.resources.displayMetrics.density, fullScale, 0.01f)
        assertEquals(4.5f * context.resources.displayMetrics.density, downscaled, 0.01f)
    }

    @Test
    fun `day label layout keeps baseline size when labels fit`() {
        val today = LocalDate.of(2026, 4, 21)

        val layout = DailyGraphLayoutResolver.resolveDayLabelLayout(
            labels = listOf(
                DailyDayLabelInput(today, "Today", isToday = true),
                DailyDayLabelInput(today.plusDays(1), "Wed"),
                DailyDayLabelInput(today.plusDays(2), "Thu"),
            ),
            baseTextSizePx = 24f,
            maxTextWidthPx = 120f,
        )

        assertEquals(1f, layout.scale, 0.0001f)
        assertEquals(24f, layout.textSizePx, 0.0001f)
        assertFalse(layout.shortenedLabels)
        assertEquals("Today", layout.textByDate[today])
    }

    @Test
    fun `day label layout shrinks labels when shortening does not apply`() {
        val today = LocalDate.of(2026, 4, 21)

        val layout = DailyGraphLayoutResolver.resolveDayLabelLayout(
            labels = listOf(
                DailyDayLabelInput(today, "Monday"),
                DailyDayLabelInput(today.plusDays(1), "Wednesday"),
                DailyDayLabelInput(today.plusDays(2), "Thu"),
            ),
            baseTextSizePx = 40f,
            maxTextWidthPx = 1f,
        )

        assertTrue("Expected labels to shrink. Layout=$layout", layout.scale < 1f)
        assertFalse("Labels should not shorten when no today label is present. Layout=$layout", layout.shortenedLabels)
        assertEquals("Monday", layout.textByDate[today])
    }

    @Test
    fun `day label layout shortens today when minimum scale is not enough`() {
        val today = LocalDate.of(2026, 4, 21)

        val layout = DailyGraphLayoutResolver.resolveDayLabelLayout(
            labels = listOf(
                DailyDayLabelInput(today, "Today", isToday = true),
                DailyDayLabelInput(today.plusDays(1), "Wed"),
                DailyDayLabelInput(today.plusDays(2), "Thu"),
            ),
            baseTextSizePx = 80f,
            maxTextWidthPx = 1f,
        )

        assertTrue(layout.shortenedLabels)
        assertEquals("Tue", layout.textByDate[today])
    }

    @Test
    fun `renderGraph daily labels do not overlap in tight three column layout`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val today = LocalDate.of(2026, 4, 21)
        val labels = mutableListOf<DailyForecastGraphRenderer.DayLabelDrawnDebug>()

        runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = listOf(
                    DailyForecastGraphRenderer.DayData(today, "Today", solidLineHigh = 69f, solidLineLow = 54f, isToday = true),
                    DailyForecastGraphRenderer.DayData(today.plusDays(1), "Wed", solidLineHigh = 65f, solidLineLow = 48f),
                    DailyForecastGraphRenderer.DayData(today.plusDays(2), "Thu", solidLineHigh = 68f, solidLineLow = 50f),
                ),
                widthPx = 430,
                heightPx = 518,
                onDayLabelDrawn = labels::add, useCelsius = false,
            )
        }

        assertEquals(3, labels.size)
        labels.zipWithNext().forEach { (left, right) ->
            assertTrue(
                "Expected adjacent day labels not to overlap. left=$left right=$right",
                left.rightX < right.leftX,
            )
        }
    }

    @Test
    fun `undrawn future snapshot does not raise the axis`() {
        // A future column never draws its prior-day snapshot (desktop Nov 3 2026: a 75° snapshot
        // over a 71° bar left the top of the graph empty).
        val today = LocalDate.of(2026, 10, 29)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(today, "Today", 66f, 48f, isToday = true, columnIndex = 0),
            DailyForecastGraphRenderer.DayData(
                today.plusDays(1), "Fri", 71f, 57f, columnIndex = 1, snapshotHigh = 75f, snapshotLow = 58f,
            ),
        )

        val layout = DailyGraphLayoutResolver.resolve(
            days = days, widthPx = 400, heightPx = 300, columns = 2,
            bitmapScale = 1f, density = 1f, useCelsius = false,
        )

        assertEquals(71f, layout.maxTemp, 0.0001f)
    }
}
