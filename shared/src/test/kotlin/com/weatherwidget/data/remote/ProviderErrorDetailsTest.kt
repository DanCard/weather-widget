package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ProviderErrorDetailsTest {
    // The Fold's 429 of 2026-10-06, as require2xx formats it.
    private val fold429 = """
        Google Weather fetch failed (/forecast/hours:lookup): status 429. Detail: {
          "error": {
            "code": 429,
            "message": "Quota exceeded for quota metric 'Weather API - Forecast Hours Usage' and limit 'Weather API - Forecast Hours Usage per day' of service 'weather.googleapis.com' for consumer 'project_number:617178968553'.",
            "status": "RESOURCE_EXHAUSTED",
            "details": [
              {
                "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                "reason": "RATE_LIMIT_EXCEEDED",
                "domain": "googleapis.com",
                "metadata": {
                  "service": "weather.googleapis.com",
                  "window_start_time": "1791270000",
                  "quota_unit": "1/d/{project}",
                  "quota_limit_value": "60",
                  "quota_limit": "ForecastHoursQueriesPerDay",
                  "quota_metric": "weather.googleapis.com/forecast/hours"
                }
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `google quota 429 yields every field a person needs`() {
        val d = ProviderErrorDetails.parse(fold429)!!
        assertEquals("/forecast/hours:lookup", d.request)
        assertEquals(429, d.httpStatus)
        assertTrue(d.providerMessage!!.startsWith("Quota exceeded for quota metric"))
        assertEquals("ForecastHoursQueriesPerDay", d.quotaName)
        assertEquals("60", d.quotaLimit)
        assertEquals(ProviderErrorDetails.QuotaPeriod.DAY, d.quotaPeriod)
        assertEquals("weather.googleapis.com/forecast/hours", d.quotaMetric)
        assertEquals(1_791_270_000_000L, d.quotaWindowStartMs)
        assertTrue(d.rawBody!!.contains("\"quota_limit_value\": \"60\""))
    }

    @Test
    fun `plain-text body is the provider message`() {
        val d = ProviderErrorDetails.parse("Tomorrow.io hourly fetch failed: status 429. Detail: Too Many Calls")!!
        assertEquals(429, d.httpStatus)
        assertNull(d.request)
        assertEquals("Too Many Calls", d.providerMessage)
        assertEquals("Too Many Calls", d.rawBody)
        assertNull(d.quotaName)
    }

    @Test
    fun `a message with no body is shown as is`() {
        val d = ProviderErrorDetails.parse("Unable to resolve host \"weather.googleapis.com\"")!!
        assertNull(d.httpStatus)
        assertEquals("Unable to resolve host \"weather.googleapis.com\"", d.providerMessage)
        assertNull(d.rawBody)
    }

    @Test
    fun `nothing stored, nothing parsed`() {
        assertNull(ProviderErrorDetails.parse(null))
        assertNull(ProviderErrorDetails.parse("  "))
    }
}
