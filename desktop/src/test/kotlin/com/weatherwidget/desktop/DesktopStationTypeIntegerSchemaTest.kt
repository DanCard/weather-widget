package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Desktop half of Room MIGRATION_75_76: v29 rebuilds observations with `stationType` as
 * [StationType.dbCode]. Every legacy spelling maps by name, junk becomes UNKNOWN, NWS_BLEND rows are
 * dropped, and the DAO reads the codes back as the enum.
 * See plans/261009-station-type-enum-integer-codes-in-db.md.
 */
@Category(ShortDuration::class)
class DesktopStationTypeIntegerSchemaTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: Path

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-station-type-int")
        dbPath = tempDir.resolve("weather.db")
    }

    @After
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `schema 28 station type strings become integer codes`() {
        // Build a real v28 database: initialize at current, then put the TEXT column back.
        DesktopWeatherDatabase(dbPath).initialize()
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE observations")
                st.execute(
                    DesktopWeatherDatabase.OBSERVATIONS_DDL.replace(
                        "stationType INTEGER NOT NULL DEFAULT 0",
                        "stationType TEXT NOT NULL DEFAULT 'UNKNOWN'",
                    ),
                )
                fun insert(id: String, type: String) = st.execute(
                    "INSERT INTO observations (stationId, stationName, timestamp, temperature, condition, " +
                        "locationLat, locationLon, distanceKm, stationType, fetchedAt, api) VALUES " +
                        "('$id', '$id', 1000, 68.0, 'Clear', 37.417, -122.089, 3.8, '$type', 1000, 'NWS')",
                )
                insert("KNUQ", "OFFICIAL")
                insert("AW020", "PERSONAL")
                insert("LOAC1", "RAWS")
                insert("ODD1", "UNKNOWN")
                insert("JUNK1", "something-else")
                insert("NWS_BLEND", "VIRTUAL")
                st.execute("PRAGMA user_version = 28")
            }
        }

        val database = DesktopWeatherDatabase(dbPath).apply { initialize() }

        database.getConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next(); assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, rs.getInt(1))
                }
                val stored = st.executeQuery("SELECT stationId, stationType, typeof(stationType) FROM observations")
                    .use { rs ->
                        buildMap {
                            while (rs.next()) {
                                assertEquals("integer", rs.getString(3))
                                put(rs.getString(1), rs.getInt(2))
                            }
                        }
                    }
                assertEquals(
                    mapOf("KNUQ" to 1, "AW020" to 2, "LOAC1" to 3, "ODD1" to 0, "JUNK1" to 0),
                    stored,
                )
            }
        }

        val read = DesktopWeatherDao(database)
            .getObservationsInRange(0L, Long.MAX_VALUE, 37.417, -122.089)
            .associate { it.stationId to it.stationType }
        assertEquals(StationType.OFFICIAL, read["KNUQ"])
        assertEquals(StationType.RAWS, read["LOAC1"])
        assertEquals(StationType.UNKNOWN, read["JUNK1"])
    }
}
