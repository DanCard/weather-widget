package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.toEntity
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.MediumDuration
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Desktop's stored daily recompute reads the hourly forecast by the shared rule
 * (`ActualsAggregator.aggregateStored`): every source over the observation window. It used to read
 * the displayed source's last 72 h only, so a non-displayed source's row had no forecast to carry a
 * station across its reading gaps, and the station dropped out of the blend.
 */
@Category(MediumDuration::class)
class DesktopDailyBlendHourlyInputTest {
    private lateinit var tempDbPath: Path
    private lateinit var dao: DesktopWeatherDao
    private val lat = LocationMatch.quantize(37.417)
    private val lon = LocationMatch.quantize(-122.089)
    private val zone = ZoneId.systemDefault()
    private val yesterday = LocalDate.now(zone).minusDays(1)
    private val yesterdayKey = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val now = System.currentTimeMillis()

    @Before
    fun setup() {
        ActualsProviderResolver.resetPreferenceSource()
        ActualsProviderResolver.resetLocationSource()
        tempDbPath = Files.createTempFile("daily-blend-hourly", ".db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(tempDbPath).apply { initialize() })
    }

    @After
    fun teardown() {
        Files.deleteIfExists(tempDbPath)
    }

    private fun at(hour: Int) = yesterday.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun reading(stationId: String, hour: Int, f: Float, km: Float) = ObservationReading(
        stationId = stationId, stationName = stationId, timestamp = at(hour), temperature = f,
        condition = "Clear", locationLat = lat, locationLon = lon, distanceKm = km,
        stationType = StationType.OFFICIAL, api = WeatherSource.OPEN_METEO.id,
    )

    @Test
    fun `a non-displayed source's row extrapolates against its own forecast`() {
        // Near station reports once; the far one keeps reporting 10 °F warmer.
        dao.upsertObservations(
            listOf(
                reading("NEAR", 10, 50f, 1f),
                reading("FAR", 10, 60f, 10f),
                reading("FAR", 11, 60f, 10f),
                reading("FAR", 12, 60f, 10f),
            ).map { it.toEntity(now) },
        )
        // Open-Meteo's flat forecast carries NEAR through 11:00 and 12:00.
        dao.upsertHourlyForecasts(
            lat, lon, WeatherSource.OPEN_METEO.id,
            (8..14).map { h ->
                HourlyForecast(
                    dateTime = at(h), temperature = 50f, condition = "Clear", source = WeatherSource.OPEN_METEO.id,
                    fetchedAt = now, locationLat = lat, locationLon = lon,
                )
            },
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

        val row = dao.getExtremesInRange(yesterdayKey, yesterdayKey, lat, lon)
            .single { it.source == WeatherSource.OPEN_METEO.id }
        val high = row.computedHighTemp!!
        // Old input (NWS hourly only): NEAR dropped at 11:00/12:00, so FAR alone set the high, 60.
        assertTrue("high stays near the near station, was $high", high < 55f)
    }
}
