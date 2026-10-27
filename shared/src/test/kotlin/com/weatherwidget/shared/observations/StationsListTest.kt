package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class StationsListTest {
    private val now = 1_791_300_000_000L
    private val hour = 3_600_000L
    private val mountainView = 37.4220 to -122.0841
    private val losGatos = 37.2358 to -121.9624 // a second site the fake proximity read returns

    private fun obs(
        id: String,
        at: Long,
        site: Pair<Double, Double> = mountainView,
        api: String = WeatherSource.NWS.id,
        distanceKm: Float = 1f,
    ) = ObservationReading(
        stationId = id, stationName = id, timestamp = at, temperature = 60f, condition = "Clear",
        locationLat = site.first, locationLon = site.second, distanceKm = distanceKm, api = api,
    )

    /** [box] is what the proximity read returns at any location; [everywhere] the unscoped read. */
    private class FakeHost(
        val box: List<ObservationReading> = emptyList(),
        val everywhere: List<ObservationReading> = emptyList(),
        val main: ObservationReading? = null,
    ) : StationsList.Host<ObservationReading> {
        override fun stationId(row: ObservationReading) = row.stationId
        override fun api(row: ObservationReading) = row.api
        override fun timestamp(row: ObservationReading) = row.timestamp
        override fun distanceKm(row: ObservationReading) = row.distanceKm
        override fun latitude(row: ObservationReading) = row.locationLat
        override fun longitude(row: ObservationReading) = row.locationLon
        override fun fetchedAt(row: ObservationReading) = row.fetchedAt
        override suspend fun near(sinceMs: Long, lat: Double, lon: Double) = box.filter { it.timestamp >= sinceMs }
        override suspend fun anywhere(sinceMs: Long) = everywhere.filter { it.timestamp >= sinceMs }
        override suspend fun latestMainReading(source: WeatherSource) = main
    }

    @Test
    fun `the box collapses to the nearest site, latest per station, nearest first`() = runBlocking {
        val host = FakeHost(
            box = listOf(
                obs("KNUQ", now - hour, distanceKm = 3f),
                obs("KNUQ", now - 2 * hour, distanceKm = 3f),
                obs("KPAO", now - hour, distanceKm = 2f),
                obs("KRHV", now - hour, site = losGatos, distanceKm = 1f), // an earlier, nearby site
            ),
        )
        val r = StationsList.load(host, WeatherSource.NWS, mountainView, now)
        assertEquals(listOf("KPAO", "KNUQ"), r.rows.map { it.stationId })
        assertEquals(now - hour, r.rows.last().timestamp)
        assertEquals(StationsList.Subtitle.NEARBY_STATIONS, r.subtitle)
        assertNull(r.staleAgeMs)
    }

    @Test
    fun `a short walk's fragment merges in, as the graph's observation reads do`() = runBlocking {
        // ~800 m away: Android's single-site collapse dropped KSJC's newer reading taken there.
        val walk = 37.429 to -122.0841
        val host = FakeHost(
            box = listOf(
                obs("KNUQ", now - 2 * hour, distanceKm = 3f),
                obs("KSJC", now - hour, site = walk, distanceKm = 9f),
            ),
        )
        val r = StationsList.load(host, WeatherSource.NWS, mountainView, now)
        assertEquals(listOf("KNUQ", "KSJC"), r.rows.map { it.stationId })
    }

    @Test
    fun `a nearer site with only backfill rows does not empty the list`() = runBlocking {
        val host = FakeHost(
            box = listOf(
                obs("NWS_MAIN", now - hour, site = mountainView), // synthetic, hidden from the list
                obs("KRHV", now - hour, site = losGatos),
            ),
        )
        val r = StationsList.load(host, WeatherSource.NWS, mountainView, now)
        assertEquals(listOf("KRHV"), r.rows.map { it.stationId })
    }

    @Test
    fun `nothing recent shows the newest stored rows with their age`() = runBlocking {
        val host = FakeHost(box = listOf(obs("KPAO", now - 30 * hour)))
        val r = StationsList.load(host, WeatherSource.NWS, mountainView, now)
        assertEquals(listOf("KPAO"), r.rows.map { it.stationId })
        assertEquals(StationsList.Subtitle.STALE, r.subtitle)
        assertEquals(30 * hour, r.staleAgeMs)
        assertEquals(
            "No recent National Weather Service observations — showing the latest, 1d ago.",
            StationsList.englishSubtitle(r, "National Weather Service"),
        )
    }

    @Test
    fun `a source with no stations shows its own main reading, NWS shows none`() = runBlocking {
        val main = obs("OPEN_METEO_MAIN", now - hour, api = WeatherSource.OPEN_METEO.id)
        val meteo = StationsList.load(FakeHost(main = main), WeatherSource.OPEN_METEO, mountainView, now)
        assertEquals(listOf(main), meteo.rows)
        assertEquals(StationsList.Subtitle.LATEST_READING, meteo.subtitle)

        val nws = StationsList.load(FakeHost(main = main), WeatherSource.NWS, mountainView, now)
        assertEquals(emptyList<ObservationReading>(), nws.rows)
        assertEquals(StationsList.Subtitle.NONE_FOUND, nws.subtitle)
    }

    @Test
    fun `no location reads every site`() = runBlocking {
        val host = FakeHost(everywhere = listOf(obs("KAUS", now - hour, site = 30.27 to -97.74)))
        val r = StationsList.load(host, WeatherSource.NWS, null, now)
        assertEquals(listOf("KAUS"), r.rows.map { it.stationId })
        assertEquals(" loc=none boxRows=1", r.diagnostic)
    }
}
