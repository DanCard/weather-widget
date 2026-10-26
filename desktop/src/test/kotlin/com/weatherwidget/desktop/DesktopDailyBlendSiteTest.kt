package com.weatherwidget.desktop

import kotlinx.coroutines.runBlocking
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.toEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Desktop's daily blend recompute writes only this site's row (`:shared` DailyBlendWrite, as
 * Android). It used to take the first row anywhere in the ~7-mile box — the Samsung 2026-08-22
 * shape, where a GPS excursion's half-day low landed on the home row 800 m away.
 */
@Category(MediumDuration::class)
class DesktopDailyBlendSiteTest {
    private lateinit var tempDbPath: Path
    private lateinit var dao: DesktopWeatherDao
    private val lat = LocationMatch.quantize(37.417)
    private val lon = LocationMatch.quantize(-122.089)
    private val now = System.currentTimeMillis()
    private val todayKey = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("daily-blend-site", ".db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(tempDbPath).apply { initialize() })
    }

    @After
    fun teardown() {
        Files.deleteIfExists(tempDbPath)
    }

    private fun reading(hoursAgo: Long, f: Float) = ObservationReading(
        stationId = "KNUQ", stationName = "Moffett", timestamp = now - hoursAgo * 3_600_000L, temperature = f,
        condition = "Clear", locationLat = lat, locationLon = lon, distanceKm = 3f,
        stationType = StationType.OFFICIAL, api = WeatherSource.NWS.id,
    )

    @Test
    fun `a neighbouring site's row is left alone and this site's is written`() {
        val neighbourLat = lat + 0.03 // inside the box, not the same site
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = todayKey, source = WeatherSource.NWS.id, locationLat = neighbourLat, locationLon = lon,
                    computedHighTemp = 66.5f, computedLowTemp = 57f, condition = "Clear", updatedAt = 1L,
                ),
            ),
        )
        dao.upsertObservations(listOf(reading(2, 61f), reading(1, 63f)).map { it.toEntity(now) })
        val repository = DesktopWeatherRepository(
            weatherService = mockk(relaxed = true),
            weatherDao = dao,
            latitude = lat,
            longitude = lon,
            weatherSource = WeatherSource.NWS.id,
            currentTimeMillis = { now },
        )

        runBlocking { repository.recomputeDailyExtremes(now) }
        val rows = dao.getExtremesInRange(todayKey, todayKey, lat, lon).filter { it.source == WeatherSource.NWS.id }
        val neighbour = rows.single { it.locationLat == neighbourLat }
        assertEquals("the neighbour's high is untouched", 66.5f, neighbour.computedHighTemp)
        assertEquals("the neighbour's low is untouched", 57f, neighbour.computedLowTemp)
        assertNotNull("this site gets its own row", rows.singleOrNull { it.locationLat == lat })
    }
}
