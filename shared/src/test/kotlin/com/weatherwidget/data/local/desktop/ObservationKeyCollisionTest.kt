package com.weatherwidget.data.local.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two sources observing the same station at the same instant must be two rows.
 *
 * This is the regression that shipped: `aviationweather` and `api.weather.gov` both serve KNUQ's
 * 20-minute reports, and with `api` outside the primary key an INSERT OR REPLACE let the METAR row
 * overwrite the NWS row and flip its provenance. The station then vanished from the NWS blend
 * entirely — measured on two devices 2026-08-23, KNUQ reduced to 1 surviving NWS row against 70
 * METAR rows, and the widget visibly oscillated as each feed took the key in turn.
 *
 * Nothing in the suite could have caught it: every other non-NWS source writes SYNTHETIC station ids
 * (`OPEN_METEO_MAIN`, `TOMORROW_IO_REALTIME`) that cannot collide, so METAR was the first source to
 * reuse a real station id and the first that could.
 */
@Category(ShortDuration::class)
class ObservationKeyCollisionTest {

    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("obs_key_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    private fun reading(api: String, temperature: Float) = DesktopObservationEntity(
        stationId = "KNUQ",
        stationName = "Moffett Fed Airfld",
        timestamp = 1_787_500_000_000L,
        temperature = temperature,
        condition = "Clear",
        locationLat = 37.417,
        locationLon = -122.089,
        distanceKm = 3.8f,
        stationType = "OFFICIAL",
        api = api,
    )

    @Test
    fun `same station and instant under two apis are two rows`() {
        dao.upsertObservations(listOf(reading("NWS", 66.2f), reading("METAR", 68.0f)))

        val rows = dao.getObservationsInRange(0, Long.MAX_VALUE, 37.417, -122.089)
            .filter { it.stationId == "KNUQ" }

        assertEquals("both feeds must survive", 2, rows.size)
        assertEquals(setOf("NWS", "METAR"), rows.map { it.api }.toSet())
        assertEquals(66.2f, rows.single { it.api == "NWS" }.temperature, 0.01f)
        assertEquals(68.0f, rows.single { it.api == "METAR" }.temperature, 0.01f)
    }

    /** Re-fetching the same feed must still replace, not accumulate duplicates. */
    @Test
    fun `the same api and instant still replaces`() {
        dao.upsertObservations(listOf(reading("NWS", 66.2f)))
        dao.upsertObservations(listOf(reading("NWS", 67.1f)))

        val rows = dao.getObservationsInRange(0, Long.MAX_VALUE, 37.417, -122.089)
            .filter { it.stationId == "KNUQ" }

        assertEquals(1, rows.size)
        assertEquals("the newer fetch wins", 67.1f, rows.single().temperature, 0.01f)
    }
}
