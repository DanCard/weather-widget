package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import javax.net.ssl.SSLException

@Category(ShortDuration::class)
class OpenMeteoHttpFallbackIntegrationTest {
    @Test
    fun `HTTPS failure falls back to HTTP, persists forecast in SQLite, and logs api_usage_stats`() = runBlocking {
        val dir = Files.createTempDirectory("open-meteo-http-fallback-int")
        val db = DesktopWeatherDatabase(dir.resolve("weather.db")).apply { initialize() }
        try {
            val dao = DesktopWeatherDao(db)
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val requests = mutableListOf<String>()

            val client = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += request.url.toString()
                        val host = request.url.host
                        if (host == "api.open-meteo.com" && request.url.protocol == URLProtocol.HTTPS) {
                            throw SSLException("simulated TLS stall")
                        }
                        val body = when {
                            host == "api.open-meteo.com" -> """
                                {
                                  "timezone": "America/Los_Angeles",
                                  "current": {"time": "2026-11-17T09:00", "temperature_2m": 58.5, "weather_code": 0},
                                  "daily": {
                                    "time": ["2026-11-17"],
                                    "temperature_2m_max": [64.0],
                                    "temperature_2m_min": [45.0],
                                    "weather_code": [0],
                                    "precipitation_probability_max": [10],
                                    "precipitation_sum": [0.0]
                                  },
                                  "hourly": {
                                    "time": ["2026-11-17T09:00"],
                                    "temperature_2m": [58.5],
                                    "weather_code": [0],
                                    "precipitation_probability": [10],
                                    "precipitation": [0.0],
                                    "cloud_cover": [15],
                                    "cloud_cover_low": [5],
                                    "cloud_cover_mid": [5],
                                    "cloud_cover_high": [5],
                                    "visibility": [16093.0]
                                  }
                                }
                            """.trimIndent()
                            host == "previous-runs-api.open-meteo.com" -> """{"timezone":"America/Los_Angeles","hourly":{"time":["2026-11-17T08:00"],"cloud_cover_previous_day1":[20]}}"""
                            else -> "{}"
                        }
                        respond(
                            content = body,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 15_000
                }
                install(HttpRequestRetry) {
                    maxRetries = 1
                    retryOnExceptionIf { _, cause -> cause is java.io.IOException }
                    constantDelay(1)
                }
                install(ResponseObserver) {
                    onResponse { response ->
                        val url = response.call.request.url
                        ApiUsageClassifier.classify(url.host, url.encodedPath, url.encodedQuery)?.let { key ->
                            val day = ApiUsageClassifier.usageDayMs(key.source, Instant.now(), ZoneId.systemDefault())
                            dao.logApiCall(day, key.source, key.endpoint, response.status.value)
                        }
                    }
                }
            }

            val svc = DesktopWeatherService(
                latitude = 37.417,
                longitude = -122.089,
                weatherSource = WeatherSource.OPEN_METEO.id,
                weatherDao = dao,
                injectedHttpClient = client,
            )
            val repo = DesktopWeatherRepository(svc, dao, 37.417, -122.089, WeatherSource.OPEN_METEO.id)
            val snapshot = repo.refresh(reason = "integration_test")

            assertTrue("forecast rows persisted", snapshot.raw.daily.isNotEmpty())
            assertTrue("https was tried first", requests.any { it.startsWith("https://api.open-meteo.com/") })
            assertTrue("http fallback succeeded", requests.any { it.startsWith("http://api.open-meteo.com/") })

            val usageCount = db.getConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT COALESCE(SUM(callCount), 0) FROM api_usage_stats WHERE apiSource = 'OPEN_METEO' AND endpoint = 'forecast' AND errorCount = 0"
                ).use { st ->
                    st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
            assertTrue("api_usage_stats recorded OPEN_METEO forecast call", usageCount >= 1)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
