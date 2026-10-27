package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.StationsList
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * The Observations window's stations list over the real desktop DB ([DesktopStationsListHost] +
 * :shared [StationsList]). Desktop used to read every row since the cutoff, anywhere: after a move it
 * listed the previous place's stations beside this one's, and with nothing in 24 h it showed an empty
 * list where Android shows the newest stored rows with their age.
 */
@Category(ShortDuration::class)
class DesktopStationsListTest {
    private val tempDbPath = Files.createTempFile("weather-stations-list-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val mountainView = 37.4220 to -122.0841
    private val austin = 30.2672 to -97.7431

    private fun row(id: String, at: Long, site: Pair<Double, Double>, api: String = WeatherSource.NWS.id) =
        DesktopObservationEntity(
            stationId = id, stationName = id, timestamp = at, temperature = 60f, condition = "Clear",
            locationLat = site.first, locationLon = site.second, distanceKm = 2f, api = api, fetchedAt = at,
        )

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private suspend fun load(source: WeatherSource = WeatherSource.NWS) =
        StationsList.load(DesktopStationsListHost(dao, mountainView.first, mountainView.second), source, mountainView, now)

    @Test
    fun `after a move only this site's stations are listed`() = runTest {
        dao.upsertObservations(listOf(row("KAUS", now - 3 * hour, austin), row("KNUQ", now - hour, mountainView)))
        val r = load()
        assertEquals(listOf("KNUQ"), r.rows.map { it.stationId })
        assertEquals(StationsList.Subtitle.NEARBY_STATIONS, r.subtitle)
    }

    @Test
    fun `nothing in 24 hours shows the newest stored rows and says how old`() = runTest {
        dao.upsertObservations(listOf(row("KNUQ", now - 30 * hour, mountainView)))
        val r = load()
        assertEquals(listOf("KNUQ"), r.rows.map { it.stationId })
        assertEquals(StationsList.Subtitle.STALE, r.subtitle)
    }

    @Test
    fun `a source with no stations shows its main reading`() = runTest {
        dao.upsertObservations(listOf(row("OPEN_METEO_MAIN", now - 60_000L, mountainView, api = WeatherSource.OPEN_METEO.id)))
        val r = load(WeatherSource.OPEN_METEO)
        assertEquals(listOf("OPEN_METEO_MAIN"), r.rows.map { it.stationId })
        assertEquals(StationsList.Subtitle.LATEST_READING, r.subtitle)
    }
}
