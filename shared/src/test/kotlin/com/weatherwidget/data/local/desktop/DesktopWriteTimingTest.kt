package com.weatherwidget.data.local.desktop

import com.weatherwidget.test.category.MediumDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `DB_WRITE_SLOW` on a real WAL database: a write stuck behind another connection's transaction names
 * itself (the victim), and the transaction that held the lock names itself (the culprit).
 * plans/261029-shared-source-switch-runner.md
 */
@Category(MediumDuration::class)
class DesktopWriteTimingTest {
    private lateinit var dbPath: Path
    private val reports = Collections.synchronizedList(mutableListOf<String>())
    private var fakeNowMs = 0L

    @Before
    fun setUp() {
        dbPath = Files.createTempFile("write_timing_test", ".db")
        DesktopWeatherDatabase(dbPath).initialize()
    }

    @After
    fun tearDown() {
        listOf("", "-wal", "-shm").forEach { Files.deleteIfExists(Path.of("$dbPath$it")) }
    }

    private fun realClockDb(waitMs: Long = 100, holdMs: Long = 300) =
        DesktopWeatherDatabase(dbPath, DesktopWriteTiming(waitMs, holdMs, report = { reports += it }))

    private fun fakeClockDb() =
        DesktopWeatherDatabase(dbPath, DesktopWriteTiming(200, 500, elapsedMs = { fakeNowMs }, report = { reports += it }))

    private fun field(line: String, name: String) = Regex("""\b$name=(\d+)""").find(line)!!.groupValues[1].toLong()

    @Test
    fun `a log insert behind another connection's transaction names both the victim and the holder`() {
        val db = realClockDb()
        val locked = CountDownLatch(1)
        val holder = thread(name = "holder") {
            db.getConnection().use { conn ->
                conn.autoCommit = false
                conn.createStatement().use { it.executeUpdate("INSERT INTO app_logs (timestamp, level, tag, message) VALUES (1, 'INFO', 'H', 'held')") }
                locked.countDown()
                Thread.sleep(500)
                conn.commit()
            }
        }
        assertTrue(locked.await(5, TimeUnit.SECONDS))
        DesktopWeatherDao(db).log("CONFIG_SAVE", "source=popup weatherSource: SILURIAN -> GOOGLE_WEATHER", "WARN")
        holder.join()

        val victim = reports.single { "kind=autocommit" in it }
        assertTrue(victim, victim.startsWith("DB_WRITE_SLOW kind=autocommit caller=DesktopWeatherDao.log ms="))
        assertTrue(victim, field(victim, "ms") >= 300)
        assertTrue(victim, "pid=${ProcessHandle.current().pid()}" in victim)
        val culprit = reports.single { "kind=txn" in it }
        assertTrue(culprit, field(culprit, "holdMs") >= 500)
        assertTrue(culprit, "thread=holder" in culprit)
    }

    @Test
    fun `a transaction holds the lock until close, not commit`() {
        val conn = fakeClockDb().getConnection()
        conn.autoCommit = false
        fakeNowMs += 100
        conn.commit()
        fakeNowMs += 450 // sqlite-jdbc's commit() began the next IMMEDIATE transaction; still held
        conn.close()

        val line = reports.single()
        assertTrue(line, line.startsWith("DB_WRITE_SLOW kind=txn caller=DesktopWriteTimingTest."))
        assertEquals(0L, field(line, "waitMs"))
        assertEquals(550L, field(line, "holdMs"))
    }

    @Test
    fun `setAutoCommit true releases the lock and ends the measurement`() {
        fakeClockDb().getConnection().use { conn ->
            conn.autoCommit = false
            fakeNowMs += 600
            conn.autoCommit = true
            fakeNowMs += 10_000 // not held any more
        }
        assertEquals(600L, field(reports.single(), "holdMs"))
    }

    @Test
    fun `fast writes and reads report nothing`() {
        val db = fakeClockDb()
        DesktopWeatherDao(db).log("TAG", "fast")
        db.getConnection().use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { it.executeUpdate("DELETE FROM app_logs WHERE tag = 'none'") }
            conn.commit()
        }
        db.getConnection().use { conn ->
            conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM app_logs").use { it.next() } }
        }
        assertTrue(reports.toString(), reports.isEmpty())
    }

    @Test
    fun `write verbs`() {
        listOf("INSERT INTO x", "  update x", "DELETE FROM x", "REPLACE INTO x", "VACUUM", "CREATE INDEX i", "DROP TABLE x", "ALTER TABLE x")
            .forEach { assertTrue(it, DesktopWriteTiming.isWrite(it)) }
        listOf("SELECT 1", "PRAGMA user_version", "WITH t AS (SELECT 1) SELECT * FROM t")
            .forEach { assertFalse(it, DesktopWriteTiming.isWrite(it)) }
    }
}
