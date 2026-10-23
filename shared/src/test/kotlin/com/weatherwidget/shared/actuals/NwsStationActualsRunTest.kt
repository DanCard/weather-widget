package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/** [NwsStationActualsRun]: the one NWS station-actuals run, against a fake host. */
@Category(ShortDuration::class)
class NwsStationActualsRunTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = LocalDate.of(2026, 10, 23).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val yesterday = LocalDate.of(2026, 10, 22).toEpochDay() * 86_400_000L

    private fun row(apiHigh: Float? = null, source: String = WeatherSource.NWS.id, provenance: String? = null) = DailyHistory(
        date = yesterday, source = source, locationLat = 37.4, locationLon = -122.0,
        computedHighTemp = 70f, computedLowTemp = 50f, condition = "Clear", updatedAt = 1L,
        apiHighTemp = apiHigh, apiLowTemp = apiHigh?.minus(20f), actualsSource = provenance,
    )

    private class FakeHost(
        val rows: List<DailyHistory>,
        val stationList: List<NwsApi.StationInfo> = listOf(NwsApi.StationInfo("KNUQ", "Moffett", 37.4, -122.05)),
        val failRequests: Boolean = false,
    ) : NwsStationActualsRun.Host {
        override val lat = 37.4
        override val lon = -122.0
        override val personalStationWeight = 1.0
        var stationCalls = 0
        val upserted = mutableListOf<DailyHistory>()
        val logs = mutableListOf<String>()
        override suspend fun dailyHistory(startMs: Long, endMs: Long) = rows
        override suspend fun stations(): List<NwsApi.StationInfo> { stationCalls++; return stationList }
        override suspend fun nwsHourly(dayStartMs: Long, dayEndMs: Long) = emptyList<HourlyForecast>()
        override suspend fun stationDay(station: NwsApi.StationInfo, startIso: String, endIso: String): List<ObservationReading> =
            if (failRequests) throw java.io.IOException("down") else emptyList()
        override suspend fun storedObservations(startMs: Long, endMs: Long) = emptyList<ObservationReading>()
        override suspend fun upsert(rows: List<DailyHistory>) { upserted += rows }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag/$level ${message.substringBefore(' ')}" }
    }

    @Test
    fun missingMeansNoApiValueOrNoProvenance() {
        assertEquals(listOf(yesterday), NwsStationActualsRun.missingDates(listOf(row(apiHigh = null))))
        assertEquals(listOf(yesterday), NwsStationActualsRun.missingDates(listOf(row(apiHigh = 70f, provenance = null))))
        assertEquals(emptyList<Long>(), NwsStationActualsRun.missingDates(listOf(row(apiHigh = 70f, provenance = "nws_station_pull"))))
        assertEquals(emptyList<Long>(), NwsStationActualsRun.missingDates(listOf(row(source = WeatherSource.OPEN_METEO.id))))
    }

    @Test
    fun nothingMissingAsksNothing() = runBlocking {
        val host = FakeHost(rows = listOf(row(apiHigh = 70f, provenance = "nws_station_pull")))
        NwsStationActualsRun.run(host, nowMs = now, zone = zone)
        assertEquals(0, host.stationCalls)
        assertEquals(emptyList<String>(), host.logs)
    }

    @Test
    fun noStationsIsNeverSilent() = runBlocking {
        val host = FakeHost(rows = listOf(row()), stationList = emptyList())
        NwsStationActualsRun.run(host, nowMs = now, zone = zone)
        assertEquals(listOf("NWS_STATION_ACTUALS_FAIL/WARN dates=1"), host.logs)
        assertTrue(host.upserted.isEmpty())
    }

    @Test
    fun aFailedRequestIsLoggedAndWritesNothing() = runBlocking {
        val host = FakeHost(rows = listOf(row()), failRequests = true)
        NwsStationActualsRun.run(host, nowMs = now, zone = zone)
        assertTrue("logs: ${host.logs}", host.logs.first().startsWith("NWS_STATION_ACTUALS_FAIL/WARN station=KNUQ"))
        assertTrue(host.logs.last().startsWith("NWS_STATION_ACTUALS_OUTCOME/DEBUG"))
        assertTrue(host.upserted.isEmpty())
    }

    @Test
    fun aCachedExtremeWritesTheApiActualWithProvenanceAndSkipsUnchangedRows() {
        val extreme = StationDailyExtremes.StationDailyExtreme(
            stationId = "KNUQ", stationName = "Moffett", distanceKm = 3f, high = 71f, low = 49f, readingCount = 24,
        )
        val written = NwsStationActualsRun.applyCached(row(), extreme, nowMs = 5L)!!
        assertEquals(71f, written.apiHighTemp)
        assertEquals(DailyActualsSource.CACHED_OBSERVATIONS.storedValue, written.actualsSource)
        assertEquals(70f, written.computedHighTemp) // the blend is not the cache's to change
        assertNull(NwsStationActualsRun.applyCached(written, extreme, nowMs = 9L))
    }
}
