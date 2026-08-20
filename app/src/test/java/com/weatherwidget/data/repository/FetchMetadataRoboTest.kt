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
    fun `current temp fetch time is isolated by quantized site`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fetchedAt = 1_800_000_000_000L
        val siteA = 37.4219 to -122.0840
        val siteB = 37.4242 to -122.0884 // ~800 m away; a different quantized site

        FetchMetadata.setLastCurrentTempFetchTime(context, siteA.first, siteA.second, fetchedAt)

        // Same site (sub-quantization jitter) reads back the stored time.
        assertEquals(
            fetchedAt,
            FetchMetadata.getLastCurrentTempFetchTime(context, siteA.first + 0.00001, siteA.second - 0.00001),
        )
        // A different site has its own, independent freshness.
        assertEquals(
            0L,
            FetchMetadata.getLastCurrentTempFetchTime(context, siteB.first, siteB.second),
        )
    }

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
