package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.toEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.MediumDuration
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop's recompute blended one `now − 8 d` window: the oldest day was only partly covered, and
 * its existing row was looked up with the window's milliseconds against UTC-midnight keys — missed,
 * planned as an insert, replaced whole. Every NWS day lost its api actual and frozen forecast, and
 * took an evening-only low, as it aged out (plans/261025-shared-daily-blend-recompute.md).
 */
@Category(MediumDuration::class)
class DesktopDailyBlendWindowEdgeTest {
    private lateinit var tempDbPath: Path
    private lateinit var dao: DesktopWeatherDao
    private val lat = LocationMatch.quantize(37.417)
    private val lon = LocationMatch.quantize(-122.089)
    private val zone = ZoneId.systemDefault()
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now(zone)
    private val edgeDay = today.minusDays(8)
    private val edgeKey = edgeDay.toEpochDay() * 86_400_000L

    @Before
    fun setup() {
        ActualsProviderResolver.resetPreferenceSource()
        ActualsProviderResolver.resetLocationSource()
        tempDbPath = Files.createTempFile("daily-blend-edge", ".db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(tempDbPath).apply { initialize() })
    }

    @After
    fun teardown() {
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `the oldest day keeps its columns and its whole-day extremes`() = runBlocking {
        // Every hour of the edge day and its neighbours: 48 °F at 06:00, 80 °F at 15:00, 60 °F otherwise.
        val readings = (-1..1).flatMap { offset ->
            val date = edgeDay.plusDays(offset.toLong())
            (0..23).map { h ->
                ObservationReading(
                    stationId = "KNUQ", stationName = "Moffett",
                    timestamp = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli(),
                    temperature = when (h) { 6 -> 48f; 15 -> 80f; else -> 60f },
                    condition = "Clear", locationLat = lat, locationLon = lon, distanceKm = 3f,
                    stationType = StationType.OFFICIAL, api = WeatherSource.NWS.id,
                )
            }
        }
        dao.upsertObservations(readings.map { it.toEntity(now) })
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = edgeKey, source = WeatherSource.NWS.id, locationLat = lat, locationLon = lon,
                    computedHighTemp = 80f, computedLowTemp = 48f, condition = "Clear", updatedAt = 1L,
                    forecastHighTemp = 86f, priorForecastHighTemp = 85f, noonCloudPercent = 20,
                    apiHighTemp = 84f, apiLowTemp = 47f, apiStationId = "KNUQ",
                ),
            ),
        )
        val repository = DesktopWeatherRepository(
            weatherService = mockk(relaxed = true),
            weatherDao = dao,
            latitude = lat,
            longitude = lon,
            weatherSource = WeatherSource.NWS.id,
            currentTimeMillis = { now },
        )

        repository.recomputeDailyExtremes(now)

        val row = dao.getExtremesInRange(edgeKey, edgeKey, lat, lon).single { it.source == WeatherSource.NWS.id }
        assertEquals("api actual survives", 84f, row.apiHighTemp)
        assertEquals("KNUQ", row.apiStationId)
        assertEquals("frozen forecast survives", 86f, row.forecastHighTemp)
        assertEquals(85f, row.priorForecastHighTemp)
        assertEquals(20, row.noonCloudPercent)
        assertEquals("the whole day's low, not the evening's", 48f, row.computedLowTemp!!, 0.5f)
    }
}
