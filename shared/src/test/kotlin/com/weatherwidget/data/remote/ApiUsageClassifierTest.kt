package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ApiUsageClassifierTest {
    @Test
    fun `google endpoints match the console's quota names`() {
        assertEquals(
            ApiUsageClassifier.Key("GOOGLE_WEATHER", "forecast/hours"),
            ApiUsageClassifier.classify("weather.googleapis.com", "/v1/forecast/hours:lookup"),
        )
        assertEquals("currentConditions", ApiUsageClassifier.endpointForPath("/v1/currentConditions:lookup"))
        assertEquals("history/hours", ApiUsageClassifier.endpointForPath("/v1/history/hours:lookup"))
    }

    @Test
    fun `coordinates and ids never reach the endpoint`() {
        assertEquals("points/{id}", ApiUsageClassifier.endpointForPath("/points/37.4168,-122.089"))
        assertEquals("gridpoints/MTR/{id}/forecast", ApiUsageClassifier.endpointForPath("/gridpoints/MTR/85,105/forecast/hourly"))
        assertEquals("stations/{id}/observations", ApiUsageClassifier.endpointForPath("/stations/AW020/observations"))
    }

    @Test
    fun `version segments are dropped`() {
        assertEquals("data/forecast", ApiUsageClassifier.endpointForPath("/data/2.5/forecast"))
        assertEquals("forecast", ApiUsageClassifier.endpointForPath("/v1/forecast"))
    }

    @Test
    fun `unknown hosts are not counted`() {
        assertNull(ApiUsageClassifier.classify("nominatim.openstreetmap.org", "/search"))
    }

    @Test
    fun `only 429 is a quota refusal`() {
        assertTrue(ApiUsageClassifier.isQuotaRefusal(429))
        assertTrue(ApiUsageClassifier.isError(429))
        assertFalse(ApiUsageClassifier.isQuotaRefusal(403))
        assertTrue(ApiUsageClassifier.isError(403))
        assertFalse(ApiUsageClassifier.isError(200))
    }
}
