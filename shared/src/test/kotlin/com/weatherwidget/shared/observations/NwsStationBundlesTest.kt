package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.format.DateTimeFormatter

/** [NwsStationBundles]: the one NWS observation pass, with the real shared fetchers over fake HTTP. */
@Category(ShortDuration::class)
class NwsStationBundlesTest {
    private val now = Instant.parse("2026-10-23T18:00:00Z")
    private fun iso(minutesAgo: Long) = DateTimeFormatter.ISO_INSTANT.format(now.minusSeconds(minutesAgo * 60))
    private fun obs(minutesAgo: Long) = NwsApi.Observation(timestamp = iso(minutesAgo), temperatureCelsius = 20f, textDescription = "Clear")

    private val knuq = NwsApi.StationInfo("KNUQ", "Moffett", 37.41, -122.05, StationType.OFFICIAL)
    private val kpao = NwsApi.StationInfo("KPAO", "Palo Alto", 37.46, -122.12, StationType.OFFICIAL)

    private class Host : NwsStationBundles.Host {
        val touched = mutableListOf<String>()
        val logs = mutableListOf<String>()
        override suspend fun touchLatest(stationId: String) { touched += stationId }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag ${message.substringBefore(' ')}" }
    }

    private fun run(
        latest: (String) -> FetchOutcome<NwsApi.Observation>,
        window: (String) -> List<NwsApi.Observation>,
        recentOnly: Boolean,
        host: Host,
    ) = runBlocking {
        val log: suspend (String, String, String) -> Unit = { _, _, _ -> }
        NwsStationBundles.fetch(
            stations = listOf(knuq, kpao),
            latitude = 37.4168,
            longitude = -122.0891,
            latestFetcher = NwsStationLatestFetcher({ latest(it) }, log, nowMs = { now.toEpochMilli() }),
            historyFetcher = NwsStationHistoryFetcher({ station, _, _ -> window(station.id) }, log, nowMs = { now.toEpochMilli() }),
            metarTextApi = null,
            windowStart = now.minusSeconds(NwsStationBundles.RECENT_WINDOW_MINUTES * 60),
            windowEnd = now,
            recentOnly = recentOnly,
            host = host,
        )
    }

    @Test
    fun theWindowsReadingsAreAllStored() {
        val bundles = run(
            latest = { FetchOutcome.Success(obs(5)) },
            window = { listOf(obs(5), obs(25), obs(45)) },
            recentOnly = true,
            host = Host(),
        )
        val knuqRows = bundles.single { it.station.id == "KNUQ" }.rows.map { it.timestamp }.toSet()
        assertEquals(3, knuqRows.size)
    }

    @Test
    fun aSilentStationOnALongWindowIsTouchedNotFailed() {
        val host = Host()
        val bundles = run(latest = { FetchOutcome.NoData }, window = { emptyList() }, recentOnly = false, host = host)
        assertTrue(bundles.isEmpty())
        assertEquals(listOf("KNUQ", "KPAO"), host.touched.sorted())
        assertTrue(host.logs.all { it.startsWith("OBS_ATTEMPT_TOUCH") })
    }

    @Test
    fun aFailedStationIsLoggedAndNotTouched() {
        val host = Host()
        run(
            latest = { FetchOutcome.Failed("timeout") },
            window = { throw java.io.IOException("down") },
            recentOnly = true,
            host = host,
        )
        assertTrue(host.touched.isEmpty())
        assertEquals(listOf("NWS_STATION_FAIL station=KNUQ", "NWS_STATION_FAIL station=KPAO"), host.logs.sorted())
    }

    @Test
    fun oneStationThrowingDoesNotLoseTheOthers() {
        val host = Host()
        val bundles = run(
            latest = { id -> if (id == "KNUQ") throw IllegalStateException("bad") else FetchOutcome.Success(obs(10)) },
            window = { listOf(obs(10)) },
            recentOnly = true,
            host = host,
        )
        assertEquals(listOf("KPAO"), bundles.map { it.station.id })
        assertTrue(host.logs.toString(), host.logs.contains("NWS_STATION_FAIL station=KNUQ"))
    }
}
