package com.weatherwidget.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One NWS current fetch reduces each touched day once, after every station is stored.
 *
 * The per-station recompute ran N reductions of the same day in parallel (5 × ~3.5 s on the Pixel),
 * and the one persisted last could have seen fewer stations than another.
 * See performance/261010-nws-current-fetch-recomputes-today-once-not-per-station.md.
 *
 * Integration: [NwsCurrentObservationUpdater] + [NwsObservationSource] + [DailyActualsStore] + Room,
 * with only the HTTP layer ([NwsApi]) faked. Completed reductions are counted through the store's
 * [ReducedSignatureStore], which records exactly one signature per finished, persisted reduction.
 */
@Category(LongDuration::class)
class NwsCurrentFetchRecomputeOnceTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val lat = 37.4168
    private val lon = -122.0891

    private val stations = listOf(
        NwsApi.StationInfo("AW020", "AW020", 37.43, -122.08, StationType.PERSONAL),
        NwsApi.StationInfo("KNUQ", "Moffett", 37.41, -122.05, StationType.OFFICIAL),
        NwsApi.StationInfo("KPAO", "Palo Alto", 37.46, -122.12, StationType.OFFICIAL),
        NwsApi.StationInfo("LOAC1", "LOAC1", 37.36, -122.03, StationType.PERSONAL),
        NwsApi.StationInfo("KSJC", "San Jose", 37.36, -121.93, StationType.OFFICIAL),
    )

    /** Counts completed reductions: the store records one signature per persisted day. */
    private class RecordingSignatures : ReducedSignatureStore {
        private val delegate = InMemoryReducedSignatureStore()
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun get(key: String) = delegate[key]
        override fun set(key: String, signature: String) {
            writes += key
            delegate[key] = signature
        }
        override fun clear() = delegate.clear()
        override val size: Int get() = delegate.size
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() = db.close()

    private fun store(signatures: ReducedSignatureStore) = DailyActualsStore(
        db.observationDao(),
        db.dailyHistoryDao(),
        db.appLogDao(),
        db.hourlyForecastDao(),
        PersonalStationWeightProvider { 1.0 },
        signatures,
    )

    private fun iso(ms: Long) = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    /** [latest] maps a station id to its latest reading (epoch ms, °C), or null for no answer. */
    private fun updater(
        dailyStore: DailyActualsStore,
        latest: (String) -> Pair<Long, Float>?,
    ): NwsCurrentObservationUpdater {
        val api = mockk<NwsApi>()
        coEvery { api.getGridPoint(any(), any()) } returns NwsApi.GridPointInfo(
            gridId = "MTR",
            gridX = 1,
            gridY = 1,
            forecastUrl = "https://example.invalid/forecast",
            observationStationsUrl = "https://example.invalid/stations",
        )
        coEvery { api.getObservationStations(any()) } returns stations
        coEvery { api.getLatestObservationDetailedResult(any(), any()) } answers {
            val reading = latest(firstArg())
            if (reading == null) {
                FetchOutcome.NoData
            } else {
                FetchOutcome.Success(
                    NwsApi.Observation(
                        timestamp = iso(reading.first),
                        temperatureCelsius = reading.second,
                        textDescription = "Clear",
                    ),
                )
            }
        }
        val source = NwsObservationSource(context, api, db.appLogDao(), synopticApi = null)
        return NwsCurrentObservationUpdater(source, db.observationDao(), db.appLogDao(), dailyStore)
    }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    /** Yesterday afternoon: always in the past and all on one local date, whatever the clock says. */
    private val day: LocalDate get() = LocalDate.now(zone).minusDays(1)

    private fun sameDayReadings(): Map<String, Pair<Long, Float>> = mapOf(
        "AW020" to (at(day, 13, 0) to 20.5f),
        "KNUQ" to (at(day, 13, 5) to 21.0f),
        "KPAO" to (at(day, 13, 10) to 19.5f),
        "LOAC1" to (at(day, 12, 20) to 22.8f),
        "KSJC" to (at(day, 13, 0) to 21.0f),
    )

    @Test
    fun `five stations on one day reduce that day exactly once`() = runBlocking {
        val signatures = RecordingSignatures()
        val readings = sameDayReadings()

        // The return value is the IDW current reading, which ignores day-old readings; what matters
        // here is what was stored and reduced.
        updater(store(signatures)) { readings[it] }.fetchNwsCurrent(lat, lon)

        assertEquals(
            "one reduction for the fetch, not one per station: ${signatures.writes}",
            1,
            signatures.writes.size,
        )
        assertEquals(5, db.observationDao().getObservationsInRange(at(day, 0), at(day, 24), lat, lon, apis = null).size)
    }

    @Test
    fun `readings on two local dates reduce each date once`() = runBlocking {
        val signatures = RecordingSignatures()
        val today = LocalDate.now(zone)
        // Just after midnight: some stations' latest report is still yesterday's. Today's start is
        // never in the future, so it is a valid "latest" at any time of day.
        val readings = mapOf(
            "AW020" to (at(day, 23, 50) to 15.0f),
            "KNUQ" to (at(day, 23, 55) to 15.5f),
            "KPAO" to (at(today, 0, 0) to 15.2f),
            "LOAC1" to (at(day, 23, 40) to 16.0f),
            "KSJC" to (at(today, 0, 0) to 15.8f),
        )

        updater(store(signatures)) { readings[it] }.fetchNwsCurrent(lat, lon)

        assertEquals("one reduction per touched date: ${signatures.writes}", 2, signatures.writes.size)
        assertEquals(2, signatures.writes.distinct().size)
    }

    @Test
    fun `no station answering reduces nothing`() = runBlocking {
        val signatures = RecordingSignatures()

        val result = updater(store(signatures)) { null }.fetchNwsCurrent(lat, lon)

        assertNull(result)
        assertEquals(0, signatures.writes.size)
    }

    @Test
    fun `the stored day equals a full reduction over every station`() = runBlocking {
        val readings = sameDayReadings()
        updater(store(RecordingSignatures())) { readings[it] }.fetchNwsCurrent(lat, lon)

        val dayMs = day.toEpochDay() * 86_400_000L
        val fromFetch = db.dailyHistoryDao().getExtremesInRange(dayMs, dayMs, lat, lon)
            .single { it.source == WeatherSource.NWS.id }

        // A forced reduction by a fresh store sees every stored row; the fetch's own result must
        // already match it — the per-station version could persist a run that missed a station.
        store(RecordingSignatures()).recomputeDailyExtremesForDay(lat, lon, day, emptyList(), force = true)
        val full = db.dailyHistoryDao().getExtremesInRange(dayMs, dayMs, lat, lon)
            .single { it.source == WeatherSource.NWS.id }

        assertEquals(full.computedHighTemp, fromFetch.computedHighTemp)
        assertEquals(full.computedLowTemp, fromFetch.computedLowTemp)
    }
}
