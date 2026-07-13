package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.isOfflineExceptionName
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Round-trips the app_logs *contract* rows (AppLogContracts.kt) through a real database: the
 * daemon writes them, the UI process reads them back and parses them, so writer format and
 * reader parsing must be pinned together. A failure here means a change broke the
 * daemon→UI channel even though each side still compiles.
 */
class AppLogsContractTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `wake event round-trips and empty db yields null`() {
        assertNull(dao.getLatestWakeEventMs())

        val before = System.currentTimeMillis()
        dao.log(WakeEventLog.TAG, WakeEventLog.message("resume:logind"), "INFO")
        val after = System.currentTimeMillis()

        val ts = dao.getLatestWakeEventMs()
        assertNotNull(ts)
        assertTrue(ts!! in before..after)
    }

    @Test
    fun `wake event ignores diagnostic resume and network rows`() {
        // The first version of getLatestWakeEventMs anchored on these diagnostic tags (filtered
        // by level) — rejected as fragile. Only the dedicated WAKE_EVENT rows may count.
        dao.log("RESUME_DETECT", "resume detected (logind) — catch-up refresh in 17000ms", "INFO")
        dao.log("NETWORK_DETECT", "connectivity restored — catch-up refresh in 4000ms", "INFO")
        dao.log("RESUME_DETECT", "gdbus logind monitor started (pid=1234)", "INFO")
        assertNull(dao.getLatestWakeEventMs())

        dao.log(WakeEventLog.TAG, WakeEventLog.message("startup"), "INFO")
        assertNotNull(dao.getLatestWakeEventMs())
    }

    @Test
    fun `offline failure classifies as offline after a full write-read-parse round-trip`() {
        // The exact exception seen right after resume: Ktor CIO's DNS failure, message = null.
        val e = java.nio.channels.UnresolvedAddressException()
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.failure("NWS", e), "WARN")

        val status = dao.getLatestCurrentTempStatus("NWS")
        assertNotNull(status)
        assertFalse(status!!.ok)
        val className = CurrentTempStatusLog.parseFailureClassName(status.message)
        assertTrue(
            "UI must classify '$className' as offline or the warm-up banner never engages",
            isOfflineExceptionName(className)
        )
    }

    @Test
    fun `source failure classifies as non-offline and keeps its detail`() {
        val e = IllegalStateException("boom [cause: parse]")
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.failure("NWS", e), "WARN")

        val status = dao.getLatestCurrentTempStatus("NWS")!!
        assertFalse(status.ok)
        assertFalse(isOfflineExceptionName(CurrentTempStatusLog.parseFailureClassName(status.message)))
        assertEquals("boom [cause: parse]", CurrentTempStatusLog.parseFailureDetail(status.message))
    }

    @Test
    fun `statuses are isolated per source and latest row wins`() {
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.failure("NWS", RuntimeException("x")), "WARN")
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok("OPEN_METEO"), "INFO")

        assertFalse(dao.getLatestCurrentTempStatus("NWS")!!.ok)
        assertTrue(dao.getLatestCurrentTempStatus("OPEN_METEO")!!.ok)
        assertNull(dao.getLatestCurrentTempStatus("SILURIAN"))

        // Recovery: a later ok row for the same source supersedes the failure.
        Thread.sleep(2)
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok("NWS"), "INFO")
        assertTrue(dao.getLatestCurrentTempStatus("NWS")!!.ok)
    }

    @Test
    fun `source id prefix cannot leak into another source's status`() {
        // LIKE 'source=NWS %' (space-terminated) — a hypothetical source whose id extends
        // another's must not satisfy the shorter id's lookup.
        dao.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok("NWS_BLEND"), "INFO")
        assertNull(dao.getLatestCurrentTempStatus("NWS"))
    }
}
