package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.MetarFetchPolicy
import com.weatherwidget.shared.util.MetarFetchWindow
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class StationNetworkRefreshRunTest {

    private class FakeWriteHost : ObservationWriteRun.Host {
        override val latitude = 37.4220
        override val longitude = -122.0841
        val written = mutableListOf<ObservationReading>()
        override suspend fun insert(rows: List<ObservationReading>) { written += rows }
        override suspend fun storedKeys(api: String, stationIds: Set<String>, startMs: Long, endMs: Long) =
            emptyList<ObservationWriteRun.StoredKey>()
        override suspend fun delete(api: String, stationId: String, timestamps: List<Long>) = 0
        override suspend fun retag(api: String, stationIds: Set<String>, stationType: StationType) = 0
        override suspend fun retireProducts(api: String) = Unit
        override suspend fun log(tag: String, message: String, level: String) = Unit
    }

    private class FakeHost(
        var bgSources: List<WeatherSource> = listOf(WeatherSource.NWS, WeatherSource.SILURIAN),
        var displayed: Set<String> = setOf(WeatherSource.NWS.id),
        var pref: (WeatherSource) -> WeatherSource? = { if (it == WeatherSource.SILURIAN) WeatherSource.METAR else null },
        var newestMs: Long? = null,
        var metarRows: List<ObservationReading> = emptyList(),
        var failFetch: Boolean = false,
    ) : StationNetworkRefreshRun.Host {
        override val lat = 37.4220
        override val lon = -122.0841
        val fetchedHours = mutableListOf<Int>()
        val writeHost = FakeWriteHost()
        val logs = mutableListOf<Triple<String, String, String>>()

        override suspend fun backgroundSources() = bgSources
        override suspend fun displayedSourceIds() = displayed
        override fun actualsPreference(source: WeatherSource) = pref(source)
        override suspend fun newestStoredTimestampMs(api: String) = newestMs
        override suspend fun fetchMetar(hours: Int): List<ObservationReading> {
            fetchedHours += hours
            if (failFetch) error("aviationweather down")
            return metarRows
        }
        override fun writeHost(): ObservationWriteRun.Host = writeHost
        override suspend fun log(tag: String, message: String, level: String) {
            logs += Triple(tag, message, level)
        }
    }

    private fun metarReading(station: String, ts: Long) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = ts,
        temperature = 64f,
        condition = "Clear",
        locationLat = 37.4220,
        locationLon = -122.0841,
        distanceKm = 4f,
        stationType = StationType.OFFICIAL,
        api = WeatherSource.METAR.id,
    )

    @Test
    fun `NONE tier fetches nothing`() = runBlocking {
        val host = FakeHost(
            bgSources = listOf(WeatherSource.NWS),
            displayed = setOf(WeatherSource.NWS.id),
            pref = { null },
        )
        val result = StationNetworkRefreshRun { 1_000_000L }
            .refreshIfDue(host, setOf(MetarFetchPolicy.Tier.PRIMARY, MetarFetchPolicy.Tier.NON_PRIMARY), reason = "test")

        assertEquals(MetarFetchPolicy.Tier.NONE, result.tier)
        assertEquals(0, result.fetched)
        assertTrue(host.fetchedHours.isEmpty())
    }

    @Test
    fun `NON_PRIMARY tier is skipped when only PRIMARY is accepted, and runs when accepted`() = runBlocking {
        val now = 1_760_000_000_000L
        val host = FakeHost(
            bgSources = listOf(WeatherSource.NWS, WeatherSource.SILURIAN),
            displayed = setOf(WeatherSource.NWS.id),
            metarRows = listOf(metarReading("KNUQ", now - 600_000L)),
        )
        val runner = StationNetworkRefreshRun { now }

        val skipped = runner.refreshIfDue(host, setOf(MetarFetchPolicy.Tier.PRIMARY), reason = "primary_only")
        assertEquals(MetarFetchPolicy.Tier.NON_PRIMARY, skipped.tier)
        assertTrue(host.fetchedHours.isEmpty())

        val ran = runner.refreshIfDue(
            host,
            setOf(MetarFetchPolicy.Tier.PRIMARY, MetarFetchPolicy.Tier.NON_PRIMARY),
            reason = "post_sync_actuals",
            hours = MetarFetchWindow.DEEP_HOURS,
        )
        assertEquals(MetarFetchPolicy.Tier.NON_PRIMARY, ran.tier)
        assertEquals(1, ran.fetched)
        assertEquals(listOf(MetarFetchWindow.DEEP_HOURS), host.fetchedHours)
        assertEquals(1, host.writeHost.written.size)
        assertTrue(
            host.logs.any {
                it.first == StationNetworkRefreshRun.TAG_STORED &&
                    "reason=post_sync_actuals" in it.second &&
                    "tier=NON_PRIMARY" in it.second &&
                    "hours=24" in it.second &&
                    "stations=KNUQ" in it.second
            },
        )
    }

    @Test
    fun `gap window sizes from newestStoredTimestampMs when explicit hours is null`() = runBlocking {
        val now = 1_760_000_000_000L
        val host = FakeHost(
            displayed = setOf(WeatherSource.SILURIAN.id),
            newestMs = now - 3 * 3_600_000L, // 3 h gap + 30 min margin -> 4 h
            metarRows = listOf(metarReading("KPAO", now - 300_000L)),
        )
        StationNetworkRefreshRun { now }.refreshIfDue(host, setOf(MetarFetchPolicy.Tier.PRIMARY), reason = "loop", hours = null)
        assertEquals(listOf(4), host.fetchedHours)
    }

    @Test
    fun `fetch exception is logged as METAR_OBS_FAIL and swallowed`() = runBlocking {
        val host = FakeHost(
            displayed = setOf(WeatherSource.SILURIAN.id),
            failFetch = true,
        )
        val result = StationNetworkRefreshRun { 1_760_000_000_000L }
            .refreshIfDue(host, setOf(MetarFetchPolicy.Tier.PRIMARY), reason = "loop", hours = 24)
        assertEquals(0, result.fetched)
        assertTrue(
            host.logs.any {
                it.first == StationNetworkRefreshRun.TAG_FAIL &&
                    "reason=loop" in it.second &&
                    "tier=PRIMARY" in it.second &&
                    "aviationweather down" in it.second
            },
        )
    }
}
