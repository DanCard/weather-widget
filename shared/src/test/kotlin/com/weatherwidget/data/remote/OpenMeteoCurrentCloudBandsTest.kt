package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class OpenMeteoCurrentCloudBandsTest {

    @Test
    fun `full forecast carries all current cloud bands for durable observation mapping`() = runBlocking {
        var requestedCurrent: String? = null
        val engine = MockEngine { request ->
            requestedCurrent = request.url.parameters["current"]
            respond(
                content = """
                    {
                      "timezone":"America/Los_Angeles",
                      "current":{
                        "time":"2026-08-27T10:15",
                        "temperature_2m":68.5,
                        "weather_code":2,
                        "cloud_cover":81,
                        "cloud_cover_low":12,
                        "cloud_cover_mid":46,
                        "cloud_cover_high":73
                      },
                      "daily":{
                        "time":[],"temperature_2m_max":[],"temperature_2m_min":[],
                        "weather_code":[],"precipitation_probability_max":[],"precipitation_sum":[]
                      },
                      "hourly":{
                        "time":[],"temperature_2m":[],"weather_code":[],
                        "precipitation_probability":[],"precipitation":[],
                        "cloud_cover":[],"cloud_cover_low":[],"cloud_cover_mid":[],"cloud_cover_high":[]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = OpenMeteoApi(HttpClient(engine), Json { ignoreUnknownKeys = true })

        val result = api.getForecast(37.42, -122.08, days = 1)

        assertTrue(requestedCurrent.orEmpty().contains("cloud_cover_mid"))
        assertTrue(requestedCurrent.orEmpty().contains("cloud_cover_high"))
        assertEquals(81, result.providerCurrentCloudCover)
        assertEquals(12, result.providerCurrentCloudCoverLow)
        assertEquals(46, result.providerCurrentCloudCoverMid)
        assertEquals(73, result.providerCurrentCloudCoverHigh)
    }
}
