package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarObservationFetcherOutcomeTest {

    @Test
    fun `detailed fetch preserves a malformed METAR response as failure`() = runBlocking {
        val engine = MockEngine { request ->
            val content = if (request.url.encodedPath.endsWith("/stationinfo")) {
                """
                    [{"id":"KNUQ","site":"Moffett Fed Airfld","lat":37.4161,"lon":-122.0492,
                      "elev":11,"country":"US","siteType":["METAR"]}]
                """.trimIndent()
            } else {
                "{not-json"
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        val cache = object : MetarObservationFetcher.StationCache {
            private var entry: MetarObservationFetcher.StationCache.Entry? = null
            override fun read(key: String) = entry
            override fun write(key: String, encoded: String, savedAtMs: Long) {
                entry = MetarObservationFetcher.StationCache.Entry(encoded, savedAtMs)
            }
        }
        val fetcher = MetarObservationFetcher(
            AviationWeatherApi(client, Json { ignoreUnknownKeys = true }),
            cache,
        ) { _, _, _ -> }

        try {
            assertTrue(
                fetcher.fetchObservationsResult(37.42, -122.08) is FetchOutcome.Failed,
            )
            assertTrue(
                "the compatibility list API still degrades safely to empty",
                fetcher.fetchObservations(37.42, -122.08).isEmpty(),
            )
        } finally {
            client.close()
        }
    }
}
