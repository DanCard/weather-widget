package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyNoonCloudCover
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.CloudCoverGraphRenderer
import com.weatherwidget.widget.ZoomStage
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Integration coverage for the emulator finding from Thursday 2026-08-27. This crosses the
 * Android persistence model, Android view handler, shared cloud-series builder, and real Android
 * bitmap renderer so a future total-first change fails at the visible graph boundary.
 */
@Category(LongDuration::class)
class OpenMeteoLowCloudViewParityIntegrationTest : RobolectricTest() {

    @Test
    fun `hourly graph and daily bar use clear low layer over reported total overcast`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val center = LocalDateTime.now().plusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0)
        val rows = (8..16).map { hour ->
            HourlyForecastEntity(
                dateTime = center.withHour(hour).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = 37.417,
                locationLon = -122.089,
                temperature = 70f,
                condition = "Cloudy",
                source = WeatherSource.OPEN_METEO.id,
                precipProbability = 0,
                cloudCover = 100,
                cloudCoverLow = 0,
                fetchedAt = System.currentTimeMillis(),
            )
        }

        val graphHours = CloudCoverViewHandler.buildCloudHourDataList(
            hourlyForecasts = rows,
            centerTime = center,
            numColumns = 8,
            displaySource = WeatherSource.OPEN_METEO,
            zoom = ZoomStage.WIDE.window(),
        )

        assertTrue("fixture hours must reach the graph boundary", graphHours.isNotEmpty())
        assertEquals(
            "hourly graph must use Open-Meteo low cloud when total cloud is high aloft",
            List(graphHours.size) { 0 },
            graphHours.map { it.cloudCover },
        )
        assertEquals(
            "daily bar must use the same Open-Meteo low-cloud value at noon",
            0,
            DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                hourly = rows.map { it.toHourlyForecast() },
                date = center.toLocalDate(),
                displaySourceId = WeatherSource.OPEN_METEO.id,
                zone = ZoneId.systemDefault(),
            ),
        )

        val bitmap = CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = graphHours,
            widthPx = 800,
            heightPx = 500,
            currentTime = center.minusDays(1),
        )

        assertEquals(800, bitmap.width)
        assertEquals(500, bitmap.height)
    }
}
