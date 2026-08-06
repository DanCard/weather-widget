package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Covers the write side of the "missing API actual" bug: shortly after midnight the NWS
 * gridpoint response still carries yesterday's maxTemperature window while yesterday's
 * minTemperature window has rolled off. persistNwsGridpointActuals must not let that partial
 * response erase a previously stored apiLowTemp (REPLACE semantics), and the ERA5 backfill
 * must treat a null high OR low as missing and fill only the absent field.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class NwsGridpointActualsStoreTest {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore

    private val lat = 37.416832
    private val lon = -122.089035
    private val keyLat = LocationMatch.quantize(lat)
    private val keyLon = LocationMatch.quantize(lon)
    private val yesterday = LocalDate.now().minusDays(1)
    private val yesterdayStr = yesterday.toString()
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

    private fun nwsRow(
        date: Long = yesterdayEpoch,
        rowLat: Double = keyLat,
        rowLon: Double = keyLon,
        apiHigh: Float? = null,
        apiLow: Float? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) = DailyHistoryEntity(
        date = date,
        source = WeatherSource.NWS.id,
        locationLat = rowLat,
        locationLon = rowLon,
        computedHighTemp = 75f,
        computedLowTemp = 58f,
        condition = "Clear",
        updatedAt = updatedAt,
        apiHighTemp = apiHigh,
        apiLowTemp = apiLow,
    )

    private suspend fun rowsAt(date: Long = yesterdayEpoch) =
        db.dailyHistoryDao().getExtremesInRange(date, date, lat, lon)
            .filter { it.source == WeatherSource.NWS.id }

    @Test
    fun `partial gridpoint response preserves existing low at the same key`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow(apiHigh = 77.2f, apiLow = 56.1f)))

        store.persistNwsGridpointActuals(
            lat,
            lon,
            NwsApi.DailyTemperatureExtremes(
                maxByDate = mapOf(yesterdayStr to 82.0f),
                minByDate = emptyMap(),
            ),
        )

        val stored = rowsAt().single { it.locationLat == keyLat && it.locationLon == keyLon }
        assertEquals(82.0f, stored.apiHighTemp)
        assertEquals("null minTemperature must not clobber the stored low", 56.1f, stored.apiLowTemp)
    }

    @Test
    fun `partial gridpoint response coalesces low from a legacy same-site fragment`() = runTest {
        // Legacy fragment at un-quantized coords (pre-quantize write) holding the complete pair —
        // the exact on-device constellation from the bug report.
        db.dailyHistoryDao().insertAll(
            listOf(nwsRow(rowLat = lat, rowLon = lon, apiHigh = 77.2f, apiLow = 56.1f)),
        )

        store.persistNwsGridpointActuals(
            lat,
            lon,
            NwsApi.DailyTemperatureExtremes(
                maxByDate = mapOf(yesterdayStr to 82.0f),
                minByDate = emptyMap(),
            ),
        )

        val stored = rowsAt().single { it.locationLat == keyLat && it.locationLon == keyLon }
        assertEquals(82.0f, stored.apiHighTemp)
        assertEquals("low should be coalesced from the legacy fragment", 56.1f, stored.apiLowTemp)
    }

    @Test
    fun `full gridpoint response overwrites both api values`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow(apiHigh = 77.2f, apiLow = 56.1f)))

        store.persistNwsGridpointActuals(
            lat,
            lon,
            NwsApi.DailyTemperatureExtremes(
                maxByDate = mapOf(yesterdayStr to 80.0f),
                minByDate = mapOf(yesterdayStr to 55.0f),
            ),
        )

        val stored = rowsAt().single { it.locationLat == keyLat && it.locationLon == keyLon }
        assertEquals(80.0f, stored.apiHighTemp)
        assertEquals(55.0f, stored.apiLowTemp)
    }

    @Test
    fun `gridpoint upsert with no values at all leaves existing row untouched`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow(apiHigh = 77.2f, apiLow = 56.1f)))

        store.persistNwsGridpointActuals(
            lat,
            lon,
            NwsApi.DailyTemperatureExtremes(maxByDate = emptyMap(), minByDate = emptyMap()),
        )

        val stored = rowsAt().single()
        assertEquals(77.2f, stored.apiHighTemp)
        assertEquals(56.1f, stored.apiLowTemp)
    }

    @Test
    fun `missing-api-actuals query reports rows missing either value`() = runTest {
        val twoDaysAgo = yesterday.minusDays(1)
        val twoDaysAgoEpoch = twoDaysAgo.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        db.dailyHistoryDao().insertAll(
            listOf(
                nwsRow(date = yesterdayEpoch, apiHigh = 82.0f, apiLow = null), // partial: missing low
                nwsRow(date = twoDaysAgoEpoch, apiHigh = 81.0f, apiLow = 57.0f), // complete
            ),
        )

        val missing = store.findNwsDatesMissingApiActuals(lat, lon, twoDaysAgoEpoch, yesterdayEpoch)

        assertEquals(listOf(yesterdayEpoch), missing.sorted())
    }

    @Test
    fun `archive backfill fills only the missing low and preserves the gridpoint high`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow(apiHigh = 82.0f, apiLow = null)))

        store.backfillNwsApiActualsFromArchive(
            lat,
            lon,
            mapOf(yesterdayEpoch to (79.0f to 55.5f)),
        )

        val stored = rowsAt().single()
        assertEquals("gridpoint high must be preserved", 82.0f, stored.apiHighTemp)
        assertEquals("archive fills only the missing low", 55.5f, stored.apiLowTemp)
    }

    @Test
    fun `archive backfill fills both fields when the row has neither`() = runTest {
        db.dailyHistoryDao().insertAll(listOf(nwsRow()))

        store.backfillNwsApiActualsFromArchive(
            lat,
            lon,
            mapOf(yesterdayEpoch to (79.0f to 55.5f)),
        )

        val stored = rowsAt().single()
        assertEquals(79.0f, stored.apiHighTemp)
        assertEquals(55.5f, stored.apiLowTemp)
    }

    @Test
    fun `archive backfill leaves complete rows untouched`() = runTest {
        val before = System.currentTimeMillis()
        db.dailyHistoryDao().insertAll(listOf(nwsRow(apiHigh = 81.0f, apiLow = 57.0f, updatedAt = before)))

        store.backfillNwsApiActualsFromArchive(
            lat,
            lon,
            mapOf(yesterdayEpoch to (79.0f to 55.5f)),
        )

        val stored = rowsAt().single()
        assertEquals(81.0f, stored.apiHighTemp)
        assertEquals(57.0f, stored.apiLowTemp)
        assertTrue("untouched row keeps its updatedAt", stored.updatedAt <= before)
    }
}
