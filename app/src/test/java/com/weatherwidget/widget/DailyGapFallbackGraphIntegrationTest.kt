package com.weatherwidget.widget

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.handlers.DailyViewLogic
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyGapFallbackGraphIntegrationTest {

    @Test
    fun `renderGraph reports generic fallback future bar as green and provider future bar as blue`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE

        val yesterdayStr = today.minusDays(1).format(fmt)
        val todayStr = today.format(fmt)
        val tomorrowStr = today.plusDays(1).format(fmt)
        val dayAfterTomorrowStr = today.plusDays(2).format(fmt)

        val weatherByDate = mapOf(
            yesterdayStr to forecast(yesterdayStr, 68f, 54f, WeatherSource.NWS),
            todayStr to forecast(todayStr, 70f, 55f, WeatherSource.NWS),
            tomorrowStr to forecast(tomorrowStr, 72f, 56f, WeatherSource.NWS),
            dayAfterTomorrowStr to forecast(dayAfterTomorrowStr, 74f, 57f, WeatherSource.GENERIC_GAP, isClimateNormal = true),
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 4,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
        )

        val drawnBars = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
        DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = days,
            widthPx = 800,
            heightPx = 300,
            bitmapScale = 1f,
            numColumns = days.size,
            onBarDrawn = drawnBars::add,
        )

        val providerBar = drawnBars.single { it.date == tomorrowStr && it.barType == "FUTURE" }
        val fallbackBar = drawnBars.single { it.date == dayAfterTomorrowStr && it.barType == "FUTURE" }

        assertEquals(Color.parseColor("#5AC8FA"), providerBar.color)
        assertEquals(Color.parseColor("#34C759"), fallbackBar.color)
    }

    private fun forecast(
        date: String,
        highTemp: Float,
        lowTemp: Float,
        source: WeatherSource,
        isClimateNormal: Boolean = false,
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = date,
            forecastDate = date,
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = "Clear",
            isClimateNormal = isClimateNormal,
            source = source.id,
            fetchedAt = 1L,
        )
    }
}
