package com.weatherwidget.widget.handlers

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.CloudCoverGraphRenderer
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(LongDuration::class)
class HourlyZoomCenteringRoboTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `temperature narrow window centers selected hour`() {
        val hours = buildHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.NARROW.window(),
        )

        assertCenteredLabel(hours.map(HourData::label), "12p")
    }

    @Test
    fun `precip narrow window centers selected hour`() {
        val hours = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.NARROW.window(),
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
            zoom = ZoomStage.NARROW.window(),
        )

        assertCenteredLabel(hours.map(CloudCoverGraphRenderer.CloudHourData::label), "12p")
    }

    @Test
    fun `temperature wide window centers selected hour`() {
        val hours = buildHourDataList(
            hourlyForecasts = sampleHourlyForecasts(count = 30),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
        )

        // With exclusive end hour, size is 24, and index 12 is still the center (offset 0 / 12p)
        assertEquals(24, hours.size)
        assertEquals("12p", hours[12].label)
    }

    @Test
    fun `narrow window width follows the configured span`() {
        // The setting's whole point: a wider span must render more hours. Labels, not pixels —
        // Robolectric has no font engine.
        fun labelsAtSpan(span: Int): List<String> = buildHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.NARROW.window(span),
        ).map(HourData::label)

        // Back-heavy split: 4h reads 2 back / 2 forward, 8h reads 4 back / 4 forward. The end hour
        // is exclusive, so a span of n yields n labels.
        assertEquals(listOf("10a", "11a", "12p", "1p"), labelsAtSpan(4))
        assertEquals(listOf("8a", "9a", "10a", "11a", "12p", "1p", "2p", "3p"), labelsAtSpan(8))
        assertEquals(8, labelsAtSpan(8).size)
        assertEquals(4, labelsAtSpan(4).size)
    }

    @Test
    fun `default narrow window is five hours reading three back`() {
        // Guards the shipped default specifically: 5h = 3 back / 2 forward around 12p.
        val labels = buildHourDataList(
            hourlyForecasts = sampleHourlyForecasts(),
            centerTime = LocalDateTime.of(2026, 3, 15, 12, 0),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.NARROW.window(),
        ).map(HourData::label)

        assertEquals(listOf("9a", "10a", "11a", "12p", "1p"), labels)
    }

    /**
     * All three hourly graphs must frame the selected hour identically at the default NARROW span.
     *
     * That default is 5h and splits back-heavy (3 back / 2 forward), so the anchor hour sits at
     * index 3 of 5 — one right of the list's midpoint, not on it. The window leans into history
     * deliberately; see ZoomStage.window.
     */
    private fun assertCenteredLabel(labels: List<String>, expected: String) {
        assertEquals(listOf("9a", "10a", "11a", "12p", "1p"), labels)
        assertEquals(expected, labels[3])
    }

    private fun sampleHourlyForecasts(count: Int = 48): List<HourlyForecastEntity> {
        val base = LocalDateTime.of(2026, 3, 15, 0, 0)
        return (0 until count).map { hourIndex ->
            val dateTime = base.plusHours(hourIndex.toLong())
            HourlyForecastEntity(
                dateTime = dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
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
