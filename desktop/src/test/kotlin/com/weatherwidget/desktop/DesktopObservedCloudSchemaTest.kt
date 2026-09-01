package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

@Category(ShortDuration::class)
class DesktopObservedCloudSchemaTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: Path

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-observed-cloud-schema")
        dbPath = tempDir.resolve("weather.db")
    }

    @After
    fun teardown() {
        Files.deleteIfExists(tempDir.resolve("weather.db-wal"))
        Files.deleteIfExists(tempDir.resolve("weather.db-shm"))
        Files.deleteIfExists(dbPath)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `schema 22 migrates flat observed cloud fields and dao round trips them`() {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE observations (
                        stationId TEXT NOT NULL,
                        stationName TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        temperature REAL NOT NULL,
                        condition TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        distanceKm REAL NOT NULL DEFAULT 0,
                        stationType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        fetchedAt INTEGER NOT NULL,
                        maxTempLast24h REAL,
                        minTempLast24h REAL,
                        api TEXT NOT NULL,
                        precipAmountMm REAL,
                        isWebFallback INTEGER NOT NULL DEFAULT 0,
                        qcFailed INTEGER NOT NULL DEFAULT 0,
                        cloudCover INTEGER,
                        cloudCoverLow INTEGER,
                        isMetar INTEGER NOT NULL DEFAULT 0,
                        rawMetar TEXT,
                        PRIMARY KEY (stationId, timestamp, locationLat, locationLon, api)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                        "condition, locationLat, locationLon, fetchedAt, api) VALUES " +
                        "('LEGACY', 'Legacy station', 1000, 60.0, 'Clear', 37.417, -122.089, 2000, 'NWS')",
                )
                stmt.execute("PRAGMA user_version = 22")
            }
        }

        val database = DesktopWeatherDatabase(dbPath).apply { initialize() }
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA user_version").use { rs ->
                    assertTrue(rs.next())
                    // Not a literal: initialize() must migrate all the way to current, whatever
                    // current is. This test is about the v22 cloud columns, not the version number.
                    assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, rs.getInt(1))
                }
                val columns = mutableMapOf<String, Triple<String, Int, String?>>()
                stmt.executeQuery("PRAGMA table_info(observations)").use { rs ->
                    while (rs.next()) {
                        columns[rs.getString("name")] = Triple(
                            rs.getString("type"),
                            rs.getInt("notnull"),
                            rs.getString("dflt_value"),
                        )
                    }
                }
                for (name in listOf(
                    "cloudCoverMid",
                    "cloudCoverHigh",
                    "cloudBaseLowMeters",
                    "cloudBaseMidMeters",
                    "cloudBaseHighMeters",
                    "cloudEnvelopeBaseMeters",
                    "cloudEnvelopeTopMeters",
                )) {
                    assertEquals("$name affinity", "INTEGER", columns.getValue(name).first)
                    assertEquals("$name nullable", 0, columns.getValue(name).second)
                }
                assertEquals("INTEGER", columns.getValue("cloudVerticalKind").first)
                assertEquals(1, columns.getValue("cloudVerticalKind").second)
                assertEquals("0", columns.getValue("cloudVerticalKind").third)

                stmt.executeQuery(
                    "SELECT cloudCoverMid, cloudCoverHigh, cloudBaseLowMeters, " +
                        "cloudBaseMidMeters, cloudBaseHighMeters, cloudEnvelopeBaseMeters, " +
                        "cloudEnvelopeTopMeters, cloudVerticalKind FROM observations " +
                        "WHERE stationId = 'LEGACY'",
                ).use { rs ->
                    assertTrue(rs.next())
                    for (index in 1..7) assertNull(rs.getObject(index))
                    assertEquals(0, rs.getInt(8))
                }
            }
        }

        val dao = DesktopWeatherDao(database)
        dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Moffett",
                    timestamp = 2_000,
                    temperature = 61f,
                    condition = "Cloudy",
                    locationLat = 37.417,
                    locationLon = -122.089,
                    api = "NWS",
                    cloudCover = 92,
                    cloudCoverLow = 44,
                    cloudCoverMid = 75,
                    cloudCoverHigh = 19,
                    cloudBaseLowMeters = 305,
                    cloudBaseMidMeters = 3_048,
                    cloudBaseHighMeters = 9_144,
                    cloudEnvelopeBaseMeters = 305,
                    cloudEnvelopeTopMeters = 10_000,
                    cloudVerticalKind = CloudVerticalKind.CUMULATIVE_LAYERS,
                ),
            ),
        )

        val stored = dao.getObservationsInRange(0, 3_000, 37.417, -122.089)
            .single { it.stationId == "KNUQ" }
        assertEquals(75, stored.cloudCoverMid)
        assertEquals(19, stored.cloudCoverHigh)
        assertEquals(305, stored.cloudBaseLowMeters)
        assertEquals(3_048, stored.cloudBaseMidMeters)
        assertEquals(9_144, stored.cloudBaseHighMeters)
        assertEquals(305, stored.cloudEnvelopeBaseMeters)
        assertEquals(10_000, stored.cloudEnvelopeTopMeters)
        assertEquals(CloudVerticalKind.CUMULATIVE_LAYERS, stored.cloudVerticalKind)

        database.getConnection().use { conn ->
            conn.createStatement().executeUpdate(
                "UPDATE observations SET cloudVerticalKind = 99 WHERE stationId = 'KNUQ'",
            )
        }
        val futureKind = dao.getObservationsInRange(0, 3_000, 37.417, -122.089)
            .single { it.stationId == "KNUQ" }
        assertEquals(CloudVerticalKind.OTHER, futureKind.cloudVerticalKind)
    }
}
