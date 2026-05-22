package com.weatherwidget.widget

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.handlers.DailyViewLogic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class DailyGapFallbackGraphIntegrationTest {

    @Test
    fun `renderGraph reports generic fallback future bar as green and provider future bar as blue`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE

        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val dayAfterTomorrow = today.plusDays(2)
        val yesterdayStr = yesterday.format(fmt)
        val todayStr = today.format(fmt)
        val tomorrowStr = tomorrow.format(fmt)
        val dayAfterTomorrowStr = dayAfterTomorrow.format(fmt)

        val weatherByDate = mapOf(
            yesterday to forecast(yesterdayStr, 68f, 54f, WeatherSource.NWS),
            today to forecast(todayStr, 70f, 55f, WeatherSource.NWS),
            tomorrow to forecast(tomorrowStr, 72f, 56f, WeatherSource.NWS),
            dayAfterTomorrow to forecast(dayAfterTomorrowStr, 74f, 57f, WeatherSource.GENERIC_GAP, isClimateNormal = true),
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 4,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
        )

        val drawnBars = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
        runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 800,
                heightPx = 300,
                bitmapScale = 1f,
                numColumns = days.size,
                onBarDrawn = drawnBars::add,
            )
        }

        val providerBar = drawnBars.single { it.date == tomorrow && it.barType == "FUTURE" }
        val fallbackBar = drawnBars.single { it.date == dayAfterTomorrow && it.barType == "FUTURE" }

        assertEquals(Color.parseColor("#F4C542"), providerBar.color) // Weather-adaptive: Clear → amber/gold
        assertEquals(Color.parseColor("#34C759"), fallbackBar.color)
    }

    @Test
    fun `renderGraph keeps forecast history bar for past day when extremes are missing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE

        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val yesterdayStr = yesterday.format(fmt)
        val todayStr = today.format(fmt)
        val tomorrowStr = tomorrow.format(fmt)

        val weatherByDate = mapOf(
            today to forecast(todayStr, 70f, 55f, WeatherSource.NWS),
            tomorrow to forecast(tomorrowStr, 72f, 56f, WeatherSource.NWS),
        )
        val forecastSnapshots = mapOf(
            yesterday to listOf(
                forecast(
                    date = yesterdayStr,
                    highTemp = 68f,
                    lowTemp = 54f,
                    source = WeatherSource.NWS,
                ),
            ),
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots.mapValues { it.value },
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = emptyMap(),
        )

        val yesterdayDay = days.single { it.date == yesterday }
        assertEquals(null, yesterdayDay.solidLineHigh)
        assertEquals(null, yesterdayDay.solidLineLow)
        assertEquals(68f, yesterdayDay.dashedLineHigh)
        assertEquals(54f, yesterdayDay.dashedLineLow)

        val drawnBars = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
        runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = days,
                widthPx = 600,
                heightPx = 300,
                bitmapScale = 1f,
                numColumns = days.size,
                onBarDrawn = drawnBars::add,
            )
        }

        assertTrue(
            "Expected forecast-history bar to render for yesterday when extremes are missing",
            drawnBars.any { it.date == yesterday && it.barType == "FORECAST_OVERLAY" },
        )
        assertFalse(
            "Expected no actual-history bar when extremes are missing",
            drawnBars.any { it.date == yesterday && it.barType == "HISTORY" },
        )
    }

    @Test
    fun `renderGraph uses yellow for today actual bar and orange for today snapshot bar`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val today = LocalDateTime.of(2030, 6, 15, 12, 0).toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day = DailyForecastGraphRenderer.DayData(
            date = today,
            label = "Today",
            solidLineHigh = 74f,
            solidLineLow = 65f,
            isToday = true,
            dashedLineHigh = 80f,
            dashedLineLow = 60f,
            snapshotHigh = 82f,
            snapshotLow = 62f,
        )

        val drawnBars = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
        runBlocking {
            DailyForecastGraphRenderer.renderGraph(
                context = context,
                days = listOf(day),
                widthPx = 400,
                heightPx = 300,
                bitmapScale = 1f,
                numColumns = 1,
                onBarDrawn = drawnBars::add,
            )
        }

        val todayBar = drawnBars.single { it.date == today && it.barType == "TODAY" }
        assertEquals(-52378, todayBar.color)
    }

    @Test
    fun `renderGraph shows rainfall amount label for rainy future day at 100 percent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val now = LocalDateTime.of(2030, 6, 15, 12, 0)
            val today = now.toLocalDate()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE

            val weatherByDate = mapOf(
                today to forecast(today.format(fmt), 70f, 55f, WeatherSource.NWS),
                today.plusDays(1) to forecast(
                    date = today.plusDays(1).format(fmt),
                    highTemp = 50f,
                    lowTemp = 30f,
                    source = WeatherSource.NWS,
                    condition = "Rain",
                    precipProbability = 100,
                    precipAmountMm = 0.0508f,
                ),
            )

            val days = DailyViewLogic.prepareGraphDays(
                now = now,
                centerDate = today,
                today = today,
                weatherByDate = weatherByDate,
                forecastSnapshots = emptyMap(),
                numColumns = 2,
                displaySource = WeatherSource.NWS,
                skipYesterday = false,
                skipHistory = false,
                hourlyForecasts = emptyList(),
            )

            val rainLabels = mutableListOf<DailyForecastGraphRenderer.RainLabelDrawnDebug>()
            runBlocking {
                DailyForecastGraphRenderer.renderGraph(
                    context = context,
                    days = days,
                    widthPx = 400,
                    heightPx = 300,
                    bitmapScale = 1f,
                    numColumns = days.size,
                    onRainLabelDrawn = rainLabels::add,
                )
            }

            val tomorrow = today.plusDays(1)
            val amountLabel = rainLabels.single { it.date == tomorrow }
            assertEquals(".002in", amountLabel.text)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun forecast(
        date: String,
        highTemp: Float,
        lowTemp: Float,
        source: WeatherSource,
        isClimateNormal: Boolean = false,
        condition: String = "Clear",
        precipProbability: Int? = null,
        precipAmountMm: Float? = null,
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            forecastDate = dateEpoch(date),
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = condition,
            isClimateNormal = isClimateNormal,
            source = source.id,
            precipProbability = precipProbability,
            precipAmountMm = precipAmountMm,
            fetchedAt = 1L,
        )
    }
}
