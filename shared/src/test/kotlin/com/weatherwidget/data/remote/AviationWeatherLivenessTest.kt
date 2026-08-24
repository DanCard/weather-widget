package com.weatherwidget.data.remote

import com.weatherwidget.shared.observations.MetarObservationMapper
import com.weatherwidget.test.category.LongDuration
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exercises the real `aviationweather.gov` endpoints end to end: discovery → filter → METAR fetch →
 * mapper → `ObservationReading`.
 *
 * This is the one risk the fixture tests cannot cover. The feed is a keyless third-party service
 * with no SLA and no versioning, so the failure mode that matters is **its payload shape changing
 * under us** — a field that becomes a string, an array that becomes an object. Fixtures captured on
 * 2026-08-23 would keep passing straight through that.
 *
 * Follows [com.weatherwidget.shared.util.ApiKeySignupUrlLivenessTest]: real requests, so it SKIPS on
 * a JUnit assumption when the network is unavailable rather than failing. A red suite on a plane
 * teaches people to ignore the suite.
 *
 * Both a US and a French location are checked, because "works outside the United States" is the
 * entire reason this transport exists.
 */
@Category(LongDuration::class)
class AviationWeatherLivenessTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun get(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "weather-widget-test (daniel.cardenas@gmail.com)")
            if (responseCode in 200..299) inputStream.bufferedReader().readText() else null
                .also { disconnect() }
        }
    } catch (e: Exception) {
        null
    }

    private fun networkAvailable(): Boolean = get("${AviationWeatherApi.BASE_URL}/metar?ids=KSJC&format=json&hours=1") != null

    /** Mountain View and Paris — the domestic case and the case NWS cannot serve at all. */
    private fun sites() = listOf(
        Triple("Mountain View", 37.4, -122.1),
        Triple("Paris", 48.85, 2.35),
    )

    @Test
    fun discoveryAndFetchProduceReadingsAtHomeAndAbroad() {
        assumeTrue("network unavailable — skipping liveness check", networkAvailable())

        for ((label, lat, lon) in sites()) {
            val bbox = AviationWeatherBbox.forLocation(lat, lon)
            val stationBody = get("${AviationWeatherApi.BASE_URL}/stationinfo?bbox=$bbox&format=json")
            assertTrue("$label: stationinfo returned nothing for bbox=$bbox", stationBody != null)

            val candidates = AviationWeatherApi.parseStationInfo(json, stationBody!!)
            assertTrue(
                "$label: stationinfo did not parse — the payload shape may have changed",
                candidates is FetchOutcome.Success,
            )
            val ranked = AviationWeatherStationFilter.nearest(
                (candidates as FetchOutcome.Success).value, lat, lon,
            )
            assertTrue("$label: no METAR-reporting station within the base box", ranked.isNotEmpty())

            val ids = ranked.joinToString(",") { it.info.id }
            val metarBody = get("${AviationWeatherApi.BASE_URL}/metar?ids=$ids&format=json&hours=3")
            assertTrue("$label: metar returned nothing for ids=$ids", metarBody != null)

            val rows = AviationWeatherApi.parseMetars(json, metarBody!!)
            assertTrue(
                "$label: metar did not parse — the payload shape may have changed",
                rows is FetchOutcome.Success,
            )

            val byId = ranked.associateBy { it.info.id }
            val readings = (rows as FetchOutcome.Success).value.mapNotNull { row ->
                byId[row.stationId]?.let { MetarObservationMapper.toReading(row, it, lat, lon) }
            }
            assertTrue("$label: no observation readings produced from ids=$ids", readings.isNotEmpty())

            readings.forEach { r ->
                assertEquals("$label: provenance", "METAR", r.api)
                assertTrue("$label: ${r.stationId} temperature implausible: ${r.temperature}",
                    r.temperature > -100f && r.temperature < 150f)
                assertTrue("$label: ${r.stationId} timestamp not in the last 24h", isRecent(r.timestamp))
                assertTrue("$label: ${r.stationId} not flagged as a METAR", r.isMetar)
                // Absent sky must stay absent — the invariant, checked against live data.
                r.cloudCoverLow?.let {
                    assertTrue("$label: cloud percent out of range: $it", it in 0..100)
                }
            }
        }
    }

    /** The `?ids=` form must return several stations from ONE request — the other half of the goal. */
    @Test
    fun oneRequestReturnsEveryRequestedStation() {
        assumeTrue("network unavailable — skipping liveness check", networkAvailable())

        val ids = listOf("KSJC", "KSFO", "KOAK", "KHWD", "KLVK")
        val body = get("${AviationWeatherApi.BASE_URL}/metar?ids=${ids.joinToString(",")}&format=json&hours=3")
        assertTrue("metar returned nothing", body != null)

        val rows = AviationWeatherApi.parseMetars(json, body!!)
        assertTrue("payload did not parse", rows is FetchOutcome.Success)
        val distinct = (rows as FetchOutcome.Success).value.map { it.stationId }.distinct()
        assertTrue(
            "expected several of $ids in one response, got $distinct",
            distinct.size >= 3,
        )
    }

    private fun isRecent(millis: Long): Boolean {
        val now = System.currentTimeMillis()
        return millis in (now - 86_400_000L)..(now + 3_600_000L)
    }
}
