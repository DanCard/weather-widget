package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TomorrowIoCloudDistanceTest {

    @Test
    fun `imperial cloud distance converts miles to rounded integer metres`() {
        assertEquals(1_609, TomorrowIoApi.imperialDistanceToMeters(1.0))
        assertEquals(15_997, TomorrowIoApi.imperialDistanceToMeters(9.94))
    }

    @Test
    fun `missing negative and non-finite cloud distances remain unknown`() {
        assertNull(TomorrowIoApi.imperialDistanceToMeters(null))
        assertNull(TomorrowIoApi.imperialDistanceToMeters(-0.1))
        assertNull(TomorrowIoApi.imperialDistanceToMeters(Double.NaN))
        assertNull(TomorrowIoApi.imperialDistanceToMeters(Double.POSITIVE_INFINITY))
    }
}
