package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ObservationWriteRunTest {
    private companion object {
        const val LAT = 37.417
        const val LON = -122.089
        const val MIN = 60_000L
        const val T0 = 1_761_400_800_000L // 2025-10-25T14:00Z, on a 10-minute boundary
    }

    /** `observations` as a map keyed like the table (site fixed). */
    private class FakeHost : ObservationWriteRun.Host {
        override val latitude = LAT
        override val longitude = LON
        val rows = linkedMapOf<Triple<String, String, Long>, ObservationReading>()
        val logs = mutableListOf<Pair<String, String>>()
        val retired = mutableListOf<String>()

        override suspend fun storedKeys(api: String, stationIds: Set<String>, startMs: Long, endMs: Long) =
            rows.values.filter { it.api == api && it.stationId in stationIds && it.timestamp >= startMs && it.timestamp < endMs }
                .map { ObservationWriteRun.StoredKey(it.stationId, it.timestamp, it.stationType) }

        override suspend fun insert(rows: List<ObservationReading>) = rows.forEach { this.rows[Triple(it.api, it.stationId, it.timestamp)] = it }

        override suspend fun delete(api: String, stationId: String, timestamps: List<Long>) =
            timestamps.count { rows.remove(Triple(api, stationId, it)) != null }

        override suspend fun retag(api: String, stationIds: Set<String>, stationType: StationType): Int {
            var changed = 0
            rows.replaceAll { _, row ->
                if (row.api == api && row.stationId in stationIds && row.stationType != stationType) {
                    changed++
                    row.copy(stationType = stationType)
                } else {
                    row
                }
            }
            return changed
        }

        override suspend fun retireProducts(api: String) { retired += api }

        override suspend fun log(tag: String, message: String, level: String) { logs += tag to message }

        fun timestamps(api: String, station: String) = rows.values.filter { it.api == api && it.stationId == station }.map { it.timestamp }.sorted()
    }

    private fun reading(
        station: String,
        timestamp: Long,
        api: String = WeatherSource.SYNOPTIC.id,
        type: StationType = StationType.PERSONAL,
        fetchedAt: Long = timestamp + MIN,
    ) = ObservationReading(
        stationId = station, stationName = station, timestamp = timestamp, temperature = 60f, condition = "",
        locationLat = 37.41681, locationLon = -122.08912, stationType = type, api = api, fetchedAt = fetchedAt,
    )

    private suspend fun FakeHost.write(readings: List<ObservationReading>, tomorrowHours: Int? = null, now: Long = T0 + 120 * MIN) =
        ObservationWriteRun(this).write(ObservationWriteRun.Write(readings, tomorrowHours), now)

    @Test
    fun `incremental fetches of a 5-minute personal station converge on one row per 10 minutes plus the newest`() = runBlocking {
        val host = FakeHost()
        // Each fetch brings the readings since the last one; a batch-only floor keeps every fetch's
        // newest row, so the stored set grows past one per bucket.
        for (fetch in 0 until 6) {
            host.write(listOf(reading("F4751", T0 + fetch * 5 * MIN)), now = T0 + fetch * 5 * MIN + MIN)
        }
        assertEquals(listOf(T0, T0 + 10 * MIN, T0 + 20 * MIN, T0 + 25 * MIN), host.timestamps(WeatherSource.SYNOPTIC.id, "F4751"))
    }

    @Test
    fun `official stations and other apis' rows are never thinned`() = runBlocking {
        val host = FakeHost()
        host.write((0 until 6).map { reading("KSJC", T0 + it * 5 * MIN, type = StationType.OFFICIAL) })
        host.write((0 until 6).map { reading("F4751", T0 + it * 5 * MIN, api = WeatherSource.NWS.id, type = StationType.PERSONAL) })
        host.write(listOf(reading("F4751", T0 + 2 * MIN)))

        assertEquals(6, host.timestamps(WeatherSource.SYNOPTIC.id, "KSJC").size)
        assertEquals(listOf(T0, T0 + 10 * MIN, T0 + 20 * MIN, T0 + 25 * MIN), host.timestamps(WeatherSource.NWS.id, "F4751"))
        assertEquals(listOf(T0 + 2 * MIN), host.timestamps(WeatherSource.SYNOPTIC.id, "F4751"))
    }

    @Test
    fun `an older history write keeps the station's newest stored row`() = runBlocking {
        val host = FakeHost()
        host.write(listOf(reading("F4751", T0 + 60 * MIN), reading("F4751", T0 + 65 * MIN)))
        host.write(listOf(reading("F4751", T0), reading("F4751", T0 + 5 * MIN)))

        assertEquals(listOf(T0, T0 + 60 * MIN, T0 + 65 * MIN), host.timestamps(WeatherSource.SYNOPTIC.id, "F4751"))
    }

    @Test
    fun `a write re-tags the station's earlier rows on every api, not only Synoptic`() = runBlocking {
        val host = FakeHost()
        host.write(listOf(reading("KXYZ", T0, api = WeatherSource.NWS.id, type = StationType.OFFICIAL)))
        val result = host.write(listOf(reading("KXYZ", T0 + 60 * MIN, api = WeatherSource.NWS.id, type = StationType.RAWS)))

        assertEquals(1, result.retagged)
        assertEquals(setOf(StationType.RAWS), host.rows.values.map { it.stationType }.toSet())
        assertTrue(host.logs.any { it.first == ObservationWriteRun.LOG_TAG && "retagged=1" in it.second })
    }

    @Test
    fun `a row keeps its own fetchedAt and takes the write time only when it has none`() = runBlocking {
        val host = FakeHost()
        host.write(listOf(reading("KSJC", T0, type = StationType.OFFICIAL, fetchedAt = T0 + 3 * MIN), reading("KPAO", T0, type = StationType.OFFICIAL, fetchedAt = 0L)), now = T0 + 9 * MIN)

        assertEquals(T0 + 3 * MIN, host.rows.values.single { it.stationId == "KSJC" }.fetchedAt)
        assertEquals(T0 + 9 * MIN, host.rows.values.single { it.stationId == "KPAO" }.fetchedAt)
        assertEquals(setOf(LAT to LON), host.rows.values.map { it.locationLat to it.locationLon }.toSet())
    }

    @Test
    fun `a Tomorrow io fetch logs its line even with no rows, and counts replacements`() = runBlocking {
        val host = FakeHost()
        host.write(emptyList(), tomorrowHours = 6)
        val fiveMinute = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID
        host.write(listOf(reading(fiveMinute, T0, api = WeatherSource.TOMORROW_IO.id, type = StationType.UNKNOWN)), tomorrowHours = 6)
        host.write(
            listOf(T0, T0 + 5 * MIN).map { reading(fiveMinute, it, api = WeatherSource.TOMORROW_IO.id, type = StationType.UNKNOWN) },
            tomorrowHours = 24,
        )

        assertEquals(
            listOf(
                TomorrowIoActuals.fetchLine(6, 0, null, null, 0),
                TomorrowIoActuals.fetchLine(6, 1, T0, T0, 0),
                TomorrowIoActuals.fetchLine(24, 2, T0, T0 + 5 * MIN, 1),
            ),
            host.logs.filter { it.first == TomorrowIoActuals.FETCH_TAG }.map { it.second },
        )
        assertEquals(listOf("TOMORROW_IO", "TOMORROW_IO", "TOMORROW_IO"), host.retired)
    }

    @Test
    fun `Tomorrow io rows written by a non-Tomorrow io fetch log no line`() = runBlocking {
        val host = FakeHost()
        host.write(listOf(reading(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, T0, api = WeatherSource.TOMORROW_IO.id, type = StationType.UNKNOWN)))
        assertEquals(emptyList<String>(), host.logs.filter { it.first == TomorrowIoActuals.FETCH_TAG }.map { it.second })
    }

    @Test
    fun `retired products are cleaned up once per written api`() = runBlocking {
        val host = FakeHost()
        host.write(
            listOf(
                reading("KSJC", T0, api = WeatherSource.NWS.id, type = StationType.OFFICIAL),
                reading("KPAO", T0, api = WeatherSource.NWS.id, type = StationType.OFFICIAL),
                reading("C8226", T0, api = WeatherSource.SYNOPTIC.id),
            ),
        )
        assertEquals(listOf(WeatherSource.NWS.id, WeatherSource.SYNOPTIC.id), host.retired)
    }
}
