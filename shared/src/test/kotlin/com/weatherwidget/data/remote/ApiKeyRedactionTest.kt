package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the credential-redaction contract: every known query-parameter key is stripped from error
 * text before it can reach `app_logs` or a bug-report email. Each test fails if the fix is
 * reverted (redaction removed) — that is the mutation check in the plan.
 */
@Category(ShortDuration::class)
class ApiKeyRedactionTest {

    @Test
    fun `token query parameter value is redacted`() {
        val text = "HttpRequestTimeoutException: timeout [url=https://api.synopticdata.com/v2/stations/timeseries?radius=37.4,-122.0,25.0&recent=1440&token=abc123SECRET&obtimezone=utc]"
        val redacted = ApiKeyRedaction.redact(text)
        assertFalse(redacted.contains("abc123SECRET"))
        assertTrue(redacted.contains("token=<redacted>"))
        assertTrue(redacted.contains("radius=37.4,-122.0,25.0"))
    }

    @Test
    fun `appid query parameter value is redacted`() {
        val text = "https://api.openweathermap.org/data/2.5/weather?lat=37&lon=-122&appid=OWMKEY123&units=imperial"
        val redacted = ApiKeyRedaction.redact(text)
        assertFalse(redacted.contains("OWMKEY123"))
        assertTrue(redacted.contains("appid=<redacted>"))
    }

    @Test
    fun `key query parameter value is redacted`() {
        val text = "WeatherAPI forecast failed: status 403. Detail: https://api.weatherapi.com/v1/forecast.json?key=WAKEY999&q=37,-122"
        val redacted = ApiKeyRedaction.redact(text)
        assertFalse(redacted.contains("WAKEY999"))
        assertTrue(redacted.contains("key=<redacted>"))
    }

    @Test
    fun `apikey query parameter value is redacted`() {
        val text = "https://api.example.com/v1?apikey=TIOKEY888&foo=1"
        val redacted = ApiKeyRedaction.redact(text)
        assertFalse(redacted.contains("TIOKEY888"))
        assertTrue(redacted.contains("apikey=<redacted>"))
    }

    @Test
    fun `api_key query parameter value is redacted`() {
        val text = "https://api.example.com/v1?api_key=SNAKE777&foo=1"
        val redacted = ApiKeyRedaction.redact(text)
        assertFalse(redacted.contains("SNAKE777"))
        assertTrue(redacted.contains("api_key=<redacted>"))
    }

    @Test
    fun `multiple credential params are all redacted`() {
        val text = "url=x?token=AAA&appid=BBB&key=CCC&apikey=DDD&api_key=EEE&safe=keep"
        val redacted = ApiKeyRedaction.redact(text)
        assertFalse(redacted.contains("AAA"))
        assertFalse(redacted.contains("BBB"))
        assertFalse(redacted.contains("CCC"))
        assertFalse(redacted.contains("DDD"))
        assertFalse(redacted.contains("EEE"))
        assertTrue(redacted.contains("safe=keep"))
    }

    @Test
    fun `text without credential params is unchanged`() {
        val text = "SYNOPTIC_FETCH stations=11 hours=24 rows=31687 stored=1636"
        assertEquals(text, ApiKeyRedaction.redact(text))
    }

    @Test
    fun `already-redacted text stays redacted`() {
        val text = "error=HttpRequestTimeoutException: timeout token=<redacted> appid=<redacted>"
        assertEquals(text, ApiKeyRedaction.redact(text))
    }

    @Test
    fun `partial word keys are not redacted`() {
        // `interactionToken=` is not a credential query parameter; only whole `token=` is.
        val text = "widget=1 token=KEEPME interactionToken=ALSOKEEP mykey=KEEPKEY"
        val redacted = ApiKeyRedaction.redact(text)
        assertTrue(redacted.contains("token=<redacted>"))
        assertTrue(redacted.contains("interactionToken=ALSOKEEP"))
        assertTrue(redacted.contains("mykey=KEEPKEY"))
        assertFalse(redacted.contains("KEEPME"))
    }

    @Test
    fun `credential value at end of string is redacted`() {
        val redacted = ApiKeyRedaction.redact("failed token=endsecret")
        assertFalse(redacted.contains("endsecret"))
        assertTrue(redacted.endsWith("token=<redacted>"))
    }

    @Test
    fun `case-insensitive parameter names are redacted`() {
        val redacted = ApiKeyRedaction.redact("url?Token=UPPER&AppId=MIXED&KEY=BOLD")
        assertFalse(redacted.contains("UPPER"))
        assertFalse(redacted.contains("MIXED"))
        assertFalse(redacted.contains("BOLD"))
    }

    @Test
    fun `FetchOutcome failed redacts exception message credentials`() {
        val e = RuntimeException(
            "Request timeout has expired [url=https://api.synopticdata.com/v2/stations/timeseries?token=LIVESECRET&recent=1440]"
        )
        val outcome = FetchOutcome.failed(e)
        assertFalse(outcome.reason.contains("LIVESECRET"))
        assertTrue(outcome.reason.contains("token=<redacted>"))
    }

    @Test
    fun `FetchOutcome Failed of redacts dynamic reason`() {
        val outcome = FetchOutcome.Failed.of("synoptic: rejected token=LEAKY")
        assertFalse(outcome.reason.contains("LEAKY"))
        assertTrue(outcome.reason.contains("token=<redacted>"))
    }

    @Test
    fun `access_token is redacted although underscore is a word character`() {
        val out = ApiKeyRedaction.redact("GET https://x.example/v1?access_token=abc123secret&q=1")
        assertFalse(out.contains("abc123secret"))
        assertTrue(out.contains("access_token=<redacted>&q=1"))
    }
}
