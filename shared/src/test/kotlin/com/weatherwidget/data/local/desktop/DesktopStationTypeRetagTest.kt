package com.weatherwidget.data.local.desktop

import com.weatherwidget.test.category.MediumDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * RAWS rows stored as OFFICIAL before 2026-10-03 must take the type the station is now fetched with:
 * the blend reads the stored type, and the gap-only Synoptic window never re-downloads old rows.
 * See plans/261003-raws-station-type.md.
 */
@Category(MediumDuration::class)
class DesktopStationTypeRetagTest {
    private lateinit var path: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setUp() {
        path = Files.createTempFile("retag", ".db")
        db = DesktopWeatherDatabase(path)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    private fun row(station: String, type: String, api: String, ts: Long) = DesktopObservationEntity(
        stationId = station,
        stationName = station,
        timestamp = ts,
        temperature = 80f,
        condition = "",
        locationLat = 37.417,
        locationLon = -122.089,
        stationType = type,
        api = api,
    )

    private fun types(): Map<String, String> = db.getConnection().use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT api, stationId, timestamp, stationType FROM observations").use { rs ->
                buildMap { while (rs.next()) put("${rs.getString(1)}|${rs.getString(2)}|${rs.getLong(3)}", rs.getString(4)) }
            }
        }
    }

    @Test
    fun `retag moves only the named stations of that api onto the new type`() {
        dao.upsertObservations(
            listOf(
                row("LOAC1", "OFFICIAL", "SYNOPTIC", 1_000L),
                row("LOAC1", "OFFICIAL", "SYNOPTIC", 2_000L),
                row("KNUQ", "OFFICIAL", "SYNOPTIC", 1_000L),
                row("LOAC1", "PERSONAL", "NWS", 1_000L),
            ),
        )

        val changed = dao.retagStationType("SYNOPTIC", setOf("LOAC1"), "RAWS")

        assertEquals(2, changed)
        assertEquals(
            mapOf(
                "SYNOPTIC|LOAC1|1000" to "RAWS",
                "SYNOPTIC|LOAC1|2000" to "RAWS",
                "SYNOPTIC|KNUQ|1000" to "OFFICIAL",
                "NWS|LOAC1|1000" to "PERSONAL",
            ),
            types(),
        )
        // Idempotent: rows already on the type are not rewritten.
        assertEquals(0, dao.retagStationType("SYNOPTIC", setOf("LOAC1"), "RAWS"))
    }
}
