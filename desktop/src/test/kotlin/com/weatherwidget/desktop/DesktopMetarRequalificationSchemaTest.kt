package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Desktop half of Android's `migrate69To70_…`, keeping the two platforms' one-shot repair in step.
 *
 * The readings are verbatim from the device database (2026-08-31). Both were stored unflagged
 * before `MetarPlausibility` existed, and the v24 migration must re-judge them without touching
 * their valid neighbours or any row that carries no raw report.
 */
@Category(ShortDuration::class)
class DesktopMetarRequalificationSchemaTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: Path

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-metar-requalification")
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
    fun `schema 23 flags stored impossible readings and leaves valid ones alone`() {
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
                        stationType TEXT NOT NULL DEFAULT 'OFFICIAL',
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
                        cloudCoverMid INTEGER,
                        cloudCoverHigh INTEGER,
                        cloudBaseLowMeters INTEGER,
                        cloudBaseMidMeters INTEGER,
                        cloudBaseHighMeters INTEGER,
                        cloudEnvelopeBaseMeters INTEGER,
                        cloudEnvelopeTopMeters INTEGER,
                        cloudVerticalKind INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (stationId, timestamp, locationLat, locationLon, api)
                    )
                    """.trimIndent(),
                )

                fun insert(id: String, ts: Long, temp: Double, raw: String?) {
                    stmt.execute(
                        "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                            "condition, locationLat, locationLon, fetchedAt, api, qcFailed, rawMetar) " +
                            "VALUES ('$id', '$id site', $ts, $temp, 'Fair', 37.417, -122.089, 2000, " +
                            "'NWS', 0, ${raw?.let { "'$it'" } ?: "NULL"})",
                    )
                }

                // Dewpoint 12 C above a temperature of 10 C — impossible.
                insert("KPAO", 2000, 50.0, "METAR KPAO 312347Z 32014G22KT 10SM SCT040 10/12 A2993")
                // Its own valid neighbour an hour earlier.
                insert("KPAO", 1000, 69.8, "KPAO 312247Z 33018G20KT 10SM SCT040 21/12 A2993")
                // Three-digit temperature field — structurally invalid.
                insert("KRHV", 3000, 48.2, "METAR KRHV 271547Z 00000KT 10SM FEW080 209/14 A2996")
                // No raw report: nothing to cross-check, must be left alone.
                insert("KNUQ", 4000, 68.0, null)

                stmt.execute("PRAGMA user_version = 23")
            }
        }

        val database = DesktopWeatherDatabase(dbPath).apply { initialize() }
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA user_version").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, rs.getInt(1))
                }

                fun qcFailedFor(id: String, ts: Long): Int =
                    stmt.executeQuery(
                        "SELECT qcFailed FROM observations WHERE stationId = '$id' AND timestamp = $ts",
                    ).use { rs ->
                        assertTrue("row $id@$ts survived the migration", rs.next())
                        rs.getInt(1)
                    }

                assertEquals("impossible dewpoint must be flagged", 1, qcFailedFor("KPAO", 2000))
                assertEquals("malformed temp group must be flagged", 1, qcFailedFor("KRHV", 3000))
                assertEquals("the valid neighbour must be untouched", 0, qcFailedFor("KPAO", 1000))
                assertEquals("a row with no raw report must be untouched", 0, qcFailedFor("KNUQ", 4000))

                // Flagged rows are kept, not deleted — the stations UI still shows them.
                stmt.executeQuery("SELECT COUNT(*) FROM observations").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(4, rs.getInt(1))
                }
            }
        }
    }
}
