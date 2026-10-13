package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.readBytes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.security.MessageDigest

/**
 * One-off replay harness (Phase 2 of plans/261013-shared-current-conditions-fetch.md): records real
 * NWS + Aviation Weather responses once, then replays them into the observation fetch so the old and
 * new code can be compared row for row. Inert unless REPLAY_DIR is set.
 */
@Category(ShortDuration::class)
class ObservationFetchReplayHarnessTest {
    @Test
    fun replay() = runBlocking {
        val dir = System.getenv("REPLAY_DIR")?.let(::File)
        assumeTrue("REPLAY_DIR not set", dir != null)
        val record = System.getenv("REPLAY_MODE") == "record"
        val out = File(System.getenv("REPLAY_OUT") ?: "replay-out.txt")
        dir!!.mkdirs()
        val real = HttpClient(CIO)

        fun keyOf(url: io.ktor.http.Url): String {
            // start/end move with the clock; everything else identifies the request.
            val query = url.parameters.entries()
                .filter { it.key != "start" && it.key != "end" }
                .sortedBy { it.key }
                .joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }
            val raw = "${url.host}${url.encodedPath}?$query"
            return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        val engine = MockEngine { request ->
            val key = keyOf(request.url)
            val body = File(dir, "$key.body")
            val status = File(dir, "$key.status")
            if (record) {
                val response = real.request(request.url.toString()) {
                    method = request.method
                    headers { request.headers.forEach { name, values -> values.forEach { append(name, it) } } }
                }
                body.writeBytes(response.readBytes())
                status.writeText(response.status.value.toString())
                File(dir, "$key.url").writeText(request.url.toString())
            }
            if (!body.exists()) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond(
                    body.readBytes(),
                    HttpStatusCode.fromValue(status.readText().trim().toInt()),
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = HttpClient(engine) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            }
        }
        val service = DesktopWeatherService(
            latitude = 37.4166014,
            longitude = -122.0888722,
            weatherSource = "NWS",
            injectedHttpClient = client,
        )
        val recent = service.fetchObservationsOnly(recentOnly = true, userLocationChange = false)
        val history = service.fetchObservationHistory(1)
        fun line(r: com.weatherwidget.data.model.ObservationReading) =
            listOf(
                r.stationId, r.stationName, r.timestamp, r.temperature, r.condition, r.api, r.isWebFallback,
                r.qcFailed, r.stationType, r.distanceKm, r.cloudCover, r.cloudCoverLow, r.cloudCoverMid,
                r.cloudCoverHigh, r.cloudVerticalKind, r.isMetar, r.rawMetar, r.maxTempLast24h, r.minTempLast24h,
                r.precipAmountMm,
            ).joinToString("|")
        out.writeText(
            buildString {
                appendLine("recent currentTemp=${recent.providerCurrentTemp} condition=${recent.providerCurrentCondition} observedAt=${recent.providerCurrentObservedAt}")
                recent.rawObservations.map(::line).sorted().forEach { appendLine("recent $it") }
                history.map(::line).sorted().forEach { appendLine("history $it") }
            },
        )
        real.close()
    }
}
