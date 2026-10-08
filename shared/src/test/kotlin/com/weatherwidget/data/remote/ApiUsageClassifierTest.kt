package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    private fun key(date: String) = LocalDate.parse(date).toEpochDay() * 86_400_000L
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val pacific = ZoneId.of("America/Los_Angeles")

    @Test
    fun `google calls are filed by the Pacific day wherever the device is`() {
        // 23:30 PDT on Oct 8 is 09:30 on Oct 9 in Kyiv: the console still counts it on Oct 8.
        val lateEveningPt = Instant.parse("2026-10-09T06:30:00Z")
        assertEquals(key("2026-10-08"), ApiUsageClassifier.usageDayMs("GOOGLE_WEATHER", lateEveningPt, kyiv))
        // Other providers keep the device's local day.
        assertEquals(key("2026-10-09"), ApiUsageClassifier.usageDayMs("NWS", lateEveningPt, kyiv))
        assertNull(ApiUsageClassifier.quotaZone("NWS"))
    }

    @Test
    fun `google day follows Pacific midnight across the PDT to PST change`() {
        val utc = ZoneId.of("UTC")
        // 2026-11-01: PDT ends at 02:00. Both instants are Nov 1 in Pacific, the second Nov 2 in UTC.
        assertEquals(key("2026-11-01"), ApiUsageClassifier.usageDayMs("GOOGLE_WEATHER", Instant.parse("2026-11-01T07:30:00Z"), utc))
        assertEquals(key("2026-11-01"), ApiUsageClassifier.usageDayMs("GOOGLE_WEATHER", Instant.parse("2026-11-02T07:30:00Z"), utc))
        assertEquals(key("2026-11-02"), ApiUsageClassifier.usageDayMs("GOOGLE_WEATHER", Instant.parse("2026-11-02T08:00:00Z"), utc))
    }

    @Test
    fun `in a Pacific zone every source files the same day as before`() {
        val now = Instant.parse("2026-10-08T15:56:00Z")
        for (source in listOf("GOOGLE_WEATHER", "NWS", "OPEN_METEO")) {
            assertEquals(key("2026-10-08"), ApiUsageClassifier.usageDayMs(source, now, pacific))
        }
    }
}
