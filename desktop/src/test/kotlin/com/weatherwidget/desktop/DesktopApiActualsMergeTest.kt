package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.toEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.test.category.MediumDuration
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * NWS api actuals used to come from the gridpoint *forecast* grid (and, when that left a gap, from
 * Open-Meteo's ERA5 archive). Both writers are gone — see
 * plans/260808-nws-actuals-forecast-contamination.md. They are now filled by a dedicated
 * `/stations/{id}/observations` pull (`fillNwsStationActualsIfNeeded`, logic in the shared
 * `NwsDailyExtremesFetch`), never derived from the stored observation pool.
 *
 * Open-Meteo's own ERA5 values are a separate, legitimate writer and still apply.
 */
@Category(MediumDuration::class)
class DesktopApiActualsMergeTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var weatherService: DesktopWeatherService
    private lateinit var repository: DesktopWeatherRepository

    private val lat = 37.416824
    private val lon = -122.08898
    private val source = WeatherSource.NWS.id
    private val zone: ZoneId = ZoneId.systemDefault()
    private val yesterday: LocalDate = LocalDate.now().minusDays(1)
    private val yesterdayEpoch = yesterday.toEpochDay() * 86_400_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-api-actuals-test", ".db")
        db = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(db)
        weatherService = mockk()
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)
    }

    @After
    fun teardown() {
        db.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun reading(
        hour: Int,
        temp: Float,
        stationId: String,
        distanceKm: Float,
        stationType: String = "OFFICIAL",
        api: String = WeatherSource.NWS.id,
    ) = ObservationReading(
        stationId = stationId,
        stationName = "$stationId name",
        timestamp = yesterday.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli(),
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = distanceKm,
        stationType = stationType,
        api = api,
    )

    /** Covers both guard windows so the station qualifies. */
    private fun coveredStation(stationId: String, distanceKm: Float, low: Float, high: Float, stationType: String = "OFFICIAL") =
        listOf(
            reading(3, low, stationId, distanceKm, stationType),
            reading(15, high, stationId, distanceKm, stationType),
            reading(20, (low + high) / 2f, stationId, distanceKm, stationType),
        )

    private fun storedNwsRow() = dao.getExtremesInRange(yesterdayEpoch, yesterdayEpoch, lat, lon)
        .single { it.source == source }

    /**
     * Regression guard: the stored observation pool is mostly Synoptic rows from the prefer-newest
     * latest path, and its NWS API subset is too sparse to carry a daily peak. api actuals must
     * come from the dedicated /stations/{id}/observations pull, never from this recompute.
     */
    @Test
    fun `recompute never derives an api actual from stored observations`() {
        val now = System.currentTimeMillis()
        dao.upsertObservations(coveredStation("KNUQ", 3.83f, 60.8f, 75.2f).map { it.toEntity(now) })

        repository.recomputeDailyExtremes(now)

        val stored = storedNwsRow()
        assertNull("the stored pool must not become an actual", stored.apiHighTemp)
        assertNull(stored.apiStationId)
    }

    /**
     * Mirrors Android's freeze guard: once a past day's actuals come from the station pull
     * (`apiStationId != null`) the recompute must not rebuild its blend from the stored pool.
     */
    @Test
    fun `a past day resolved from the pull keeps its blend across a recompute`() {
        val now = System.currentTimeMillis()
        dao.upsertObservations(coveredStation("KNUQ", 3.83f, 60.8f, 75.2f).map { it.toEntity(now) })
        repository.recomputeDailyExtremes(now)
        dao.upsertDailyHistory(
            listOf(
                storedNwsRow().copy(
                    computedHighTemp = 74.6f,
                    computedLowTemp = 60.5f,
                    apiStationId = "KNUQ",
                    apiStationDistanceKm = 3.83f,
                    apiHighTemp = 75.2f,
                    apiLowTemp = 60.8f,
                    // The freeze marker is provenance, not station identity.
                    actualsSource = DailyActualsSource.NWS_STATION_PULL.storedValue,
                ),
            ),
        )

        // TWICE — see the Android sibling: one pass passes even when the merge drops actualsSource.
        repository.recomputeDailyExtremes(now + 1000)
        repository.recomputeDailyExtremes(now + 2000)

        val row = storedNwsRow()
        assertEquals("frozen once resolved from the endpoint", 74.6f, row.computedHighTemp)
        assertEquals(
            DailyActualsSource.NWS_STATION_PULL.storedValue,
            row.actualsSource,
        )
    }

    /** A cache-derived day's blend comes from the stored pool, so the recompute keeps owning it. */
    @Test
    fun `a cache-resolved day does not freeze its blend`() {
        val now = System.currentTimeMillis()
        dao.upsertObservations(coveredStation("KNUQ", 3.83f, 60.8f, 75.2f).map { it.toEntity(now) })
        repository.recomputeDailyExtremes(now)
        dao.upsertDailyHistory(
            listOf(
                storedNwsRow().copy(
                    computedHighTemp = 99.9f,
                    apiHighTemp = 75.2f,
                    apiLowTemp = 60.8f,
                    apiStationId = "KNUQ",
                    actualsSource = DailyActualsSource.CACHED_OBSERVATIONS.storedValue,
                ),
            ),
        )

        repository.recomputeDailyExtremes(now + 1000)

        assertNotEquals(
            "a cache-derived blend must stay recomputable",
            99.9f,
            storedNwsRow().computedHighTemp,
        )
    }

    @Test
    fun `an api actual survives a later blend recompute`() {
        val now = System.currentTimeMillis()
        dao.upsertObservations(coveredStation("KNUQ", 3.83f, 60.8f, 75.2f).map { it.toEntity(now) })
        repository.recomputeDailyExtremes(now)
        val seeded = storedNwsRow().copy(
            apiHighTemp = 75.2f,
            apiLowTemp = 60.8f,
            apiStationId = "KNUQ",
            apiStationDistanceKm = 3.83f,
        )
        dao.upsertDailyHistory(listOf(seeded))

        repository.recomputeDailyExtremes(now + 1000)

        val stored = storedNwsRow()
        assertEquals("the pull owns this field", 75.2f, stored.apiHighTemp)
        assertEquals("KNUQ", stored.apiStationId)
    }

}
