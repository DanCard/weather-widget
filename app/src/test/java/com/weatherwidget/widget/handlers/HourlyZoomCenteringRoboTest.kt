package com.weatherwidget.widget.handlers

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.CloudCoverGraphRenderer
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HourlyZoomCenteringRoboTest {

    private lateinit var context: Context
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `temperature narrow window centers selected hour`() {
        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
        )

        assertCenteredLabel(hours.map(TemperatureGraphRenderer.HourData::label), "12p")
    }

    @Test
    fun `precip narrow window centers selected hour`() {
        val hours = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
        )

        assertCenteredLabel(hours.map(PrecipitationGraphRenderer.PrecipHourData::label), "12p")
    }

    @Test
    fun `cloud cover narrow window centers selected hour`() {
        val hours = CloudCoverViewHandler.buildCloudHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
        )

        assertCenteredLabel(hours.map(CloudCoverGraphRenderer.CloudHourData::label), "12p")
    }

    private fun assertCenteredLabel(labels: List<String>, expected: String) {
        assertEquals(listOf("10a", "11a", "12p", "1p", "2p"), labels)
        assertEquals(expected, labels[labels.size / 2])
    }

    private fun sampleHourlyForecasts(): List<HourlyForecastEntity> {
        val base = LocalDateTime.of(2026, 3, 15, 8, 0)
        return (0..8).map { hourIndex ->
            val dateTime = base.plusHours(hourIndex.toLong())
            HourlyForecastEntity(
                dateTime = dateTime.format(formatter),
                locationLat = 37.42,
                locationLon = -122.08,
                temperature = 50f + hourIndex,
                condition = "Partly Cloudy",
                source = WeatherSource.NWS.id,
                precipProbability = 10 + hourIndex,
                cloudCover = 55 + hourIndex,
                fetchedAt = 1L,
            )
        }
    }
}
