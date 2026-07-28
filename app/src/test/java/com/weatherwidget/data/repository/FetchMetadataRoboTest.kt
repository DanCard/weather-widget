package com.weatherwidget.data.repository

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class FetchMetadataRoboTest : RobolectricTest() {

    @Test
    fun `forecast source success is isolated by source and quantized site`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fetchedAt = 1_800_000_000_000L
        val lat = 37.4219
        val lon = -122.0840

        FetchMetadata.setLastForecastSourceSuccessTime(
            context = context,
            sourceId = WeatherSource.NWS.id,
            latitude = lat,
            longitude = lon,
            time = fetchedAt,
        )

        assertEquals(
            fetchedAt,
            FetchMetadata.getLastForecastSourceSuccessTime(
                context,
                WeatherSource.NWS.id,
                lat + 0.00001,
                lon - 0.00001,
            ),
        )
        assertEquals(
            0L,
            FetchMetadata.getLastForecastSourceSuccessTime(
                context,
                WeatherSource.OPEN_METEO.id,
                lat,
                lon,
            ),
        )
        assertEquals(
            0L,
            FetchMetadata.getLastForecastSourceSuccessTime(
                context,
                WeatherSource.NWS.id,
                40.7128,
                -74.0060,
            ),
        )
    }
}
