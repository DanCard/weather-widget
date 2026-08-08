package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.NwsDailyExtremesFetch
import com.weatherwidget.shared.actuals.StationDailyExtremes
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * The storage side of NWS daily actuals. The *values* come from a dedicated
 * `/stations/{id}/observations` pull (see `NwsApiDailyActualsFetcher` and the shared
 * `NwsDailyExtremesFetchTest`); this covers what the store does with them, and — importantly —
 * that the blend recompute never invents them from the stored observation pool.
 * See plans/260808-nws-actuals-forecast-contamination.md.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class NwsStationActualsStoreTest {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore

    private val lat = 37.416832
    private val lon = -122.089035
    private val keyLat = LocationMatch.quantize(lat)
    private val keyLon = LocationMatch.quantize(lon)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val yesterday: LocalDate = LocalDate.now().minusDays(1)
    private val yesterdayEpoch = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY

    @Before
    fun setup() {
        db = TestDatabase.create()
        store = DailyActualsStore(
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
            db.hourlyForecastDao(),
            mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun observation(hour: Int, temp: Float, stationId: String, distanceKm: Float) =
        ObservationEntity(
            stationId = stationId,
            timestamp = yesterday.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli(),
            locationLat = lat,
            locationLon = lon,
            stationName = "$stationId name",
            temperature = temp,
            condition = "Clear",
            distanceKm = distanceKm,
            stationType = "OFFICIAL",
            api = WeatherSource.NWS.id,
            fetchedAt = System.currentTimeMillis(),
        )

    private fun nwsRow(
        rowLat: Double = keyLat,
        rowLon: Double = keyLon,
        apiHigh: Float? = null,
        apiLow: Float? = null,
    ) = DailyHistoryEntity(
        date = yesterdayEpoch,
        source = WeatherSource.NWS.id,
        locationLat = rowLat,
        locationLon = rowLon,
        computedHighTemp = 75f,
        computedLowTemp = 60f,
        condition = "Clear",
        updatedAt = System.currentTimeMillis(),
        apiHighTemp = apiHigh,
        apiLowTemp = apiLow,
    )

    private suspend fun nwsRows() =
        db.dailyHistoryDao().getExtremesInRange(yesterdayEpoch, yesterdayEpoch, lat, lon)
            .filter { it.source == WeatherSource.NWS.id }

    private fun extreme(stationId: String = "KNUQ", high: Float = 75.2f, low: Float = 60.8f) =
        StationDailyExtremes.StationDailyExtreme(
            stationId = stationId,
            stationName = "$stationId name",
            distanceKm = 3.83f,
            high = high,
            low = low,
            readingCount = 71,
        )

    private fun actuals(
        blendHigh: Float = 74.6f,
        blendLow: Float = 60.5f,
        station: StationDailyExtremes.StationDailyExtreme? = extreme(),
    ) = NwsDailyExtremesFetch.DailyActualsFromStations(blendHigh, blendLow, station)

    /**
     * Regression guard for the reason the dedicated pull exists. The stored pool is mostly
     * Synoptic rows from the prefer-newest latest path, and its NWS API subset is too sparse to
     * carry a peak (measured at KNUQ: 17-24 of the endpoint's 72 readings, missing two days'
     * maxima by 1.8 °F). The recompute must therefore never write api actuals.
     */
    @Test
    fun `blend recompute never derives an api actual from stored observations`() = runTest {
        db.observationDao().insertAll(
            listOf(
                observation(3, 60.8f, "KNUQ", 3.83f),
                observation(15, 73.4f, "KNUQ", 3.83f), // a thin sample that misses the real 75.2 peak
                observation(20, 66.0f, "KNUQ", 3.83f),
            ),
        )

        store.recomputeDailyExtremesForDay(lat, lon, yesterday, emptyList())

        val row = nwsRows().single()
        assertNull("the sparse stored pool must not become an actual", row.apiHighTemp)
        assertNull(row.apiStationId)
        assertTrue("the blend itself is still computed", row.computedHighTemp > 0f)
    }

    @Test
    fun `persist writes the station extreme onto every same-date NWS fragment`() = runTest {
        // Legacy un-quantized fragment alongside the quantized one, same physical site.
        db.dailyHistoryDao().insertAll(listOf(nwsRow(), nwsRow(rowLat = lat, rowLon = lon)))

        store.persistNwsDailyActuals(lat, lon, mapOf(yesterdayEpoch to actuals()))

        val rows = nwsRows()
        assertEquals(2, rows.size)
        rows.forEach {
            assertEquals(75.2f, it.apiHighTemp)
            assertEquals(60.8f, it.apiLowTemp)
            assertEquals("KNUQ", it.apiStationId)
            assertEquals(3.83f, it.apiStationDistanceKm)
        }
    }

    @Test
    fun `persist writes the blend from the pull, not the stored-pool value`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow()))

        store.persistNwsDailyActuals(lat, lon, mapOf(yesterdayEpoch to actuals()))

        val row = nwsRows().single()
        assertEquals("blend must come from the API pull", 74.6f, row.computedHighTemp)
        assertEquals(60.5f, row.computedLowTemp)
        assertEquals(75.2f, row.apiHighTemp)
    }

    @Test
    fun `a day with a blend but no qualifying station keeps its stored api actual`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow(apiHigh = 70f, apiLow = 55f)))

        store.persistNwsDailyActuals(lat, lon, mapOf(yesterdayEpoch to actuals(station = null)))

        val row = nwsRows().single()
        assertEquals("the blend still lands", 74.6f, row.computedHighTemp)
        assertEquals("the api actual is left alone", 70f, row.apiHighTemp)
        assertNull(row.apiStationId)
    }

    /**
     * Without the freeze guard the ordinary recompute — which runs on widget loads and
     * history-screen opens — would immediately overwrite the API-derived blend with one rebuilt
     * from the stored, part-Synoptic pool.
     */
    @Test
    fun `a past day resolved from the pull keeps its blend across a recompute`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow()))
        store.persistNwsDailyActuals(lat, lon, mapOf(yesterdayEpoch to actuals()))
        db.observationDao().insertAll(
            listOf(
                observation(3, 60.8f, "KNUQ", 3.83f),
                observation(15, 73.4f, "KNUQ", 3.83f), // a thinner pool that would blend lower
            ),
        )

        store.recomputeDailyExtremesForDay(lat, lon, yesterday, emptyList())

        assertEquals("frozen once resolved from the endpoint", 74.6f, nwsRows().first().computedHighTemp)
    }

    @Test
    fun `a past day never resolved from the pull is still recomputed normally`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow()))
        db.observationDao().insertAll(
            listOf(
                observation(3, 60.8f, "KNUQ", 3.83f),
                observation(15, 73.4f, "KNUQ", 3.83f),
            ),
        )

        store.recomputeDailyExtremesForDay(lat, lon, yesterday, emptyList())

        val row = nwsRows().first()
        assertNull("guard must not apply without the marker", row.apiStationId)
        assertNotEquals("the seeded 75f must have been recomputed", 75f, row.computedHighTemp)
    }

    @Test
    fun `persist leaves other sources untouched`() = runTest {
        db.dailyHistoryDao().insertAll(
            listOf(
                nwsRow(),
                nwsRow().copy(source = WeatherSource.OPEN_METEO.id, apiHighTemp = 74.0f, apiLowTemp = 60.4f),
            ),
        )

        store.persistNwsDailyActuals(lat, lon, mapOf(yesterdayEpoch to actuals()))

        val meteo = db.dailyHistoryDao().getExtremesInRange(yesterdayEpoch, yesterdayEpoch, lat, lon)
            .single { it.source == WeatherSource.OPEN_METEO.id }
        assertEquals("Open-Meteo's ERA5 actual is a different writer's row", 74.0f, meteo.apiHighTemp)
        assertNull(meteo.apiStationId)
    }

    @Test
    fun `an api actual survives a later blend recompute`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow()))
        store.persistNwsDailyActuals(lat, lon, mapOf(yesterdayEpoch to actuals()))
        db.observationDao().insertAll(
            listOf(
                observation(3, 60.8f, "KNUQ", 3.83f),
                observation(15, 73.4f, "KNUQ", 3.83f),
            ),
        )

        store.recomputeDailyExtremesForDay(lat, lon, yesterday, emptyList())

        val row = nwsRows().first()
        assertEquals("the pull owns this field; the recompute must carry it through", 75.2f, row.apiHighTemp)
        assertEquals("KNUQ", row.apiStationId)
    }

    @Test
    fun `missing-date query returns only NWS rows lacking a complete pair`() = runTest {
        val other = yesterday.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        db.dailyHistoryDao().insertAll(
            listOf(
                nwsRow(),                                                    // both null  -> missing
                nwsRow().copy(date = other, apiHighTemp = 75.2f),            // partial    -> missing
                nwsRow().copy(source = WeatherSource.OPEN_METEO.id),         // not NWS    -> ignored
            ),
        )

        val missing = store.findNwsDatesMissingStationActuals(lat, lon, other, yesterdayEpoch)

        assertEquals(setOf(yesterdayEpoch, other), missing.toSet())
    }

    @Test
    fun `a complete row is not reported as missing`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow(apiHigh = 75.2f, apiLow = 60.8f)))

        val missing = store.findNwsDatesMissingStationActuals(lat, lon, yesterdayEpoch, yesterdayEpoch)

        assertTrue(missing.isEmpty())
    }
}
