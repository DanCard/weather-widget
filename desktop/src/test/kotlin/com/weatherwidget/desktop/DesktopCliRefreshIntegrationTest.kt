package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * [DesktopCli] end to end: a real [DaemonFetchServer] on a Unix socket in a temp dir with a recording
 * daemon side, and a temp-file SQLite DAO holding the fetch times the freshness partition reads. Never the
 * user's database or daemon (plans/261124-desktop-cli-refresh-hook.md).
 */
@Category(ShortDuration::class)
class DesktopCliRefreshIntegrationTest {
    private lateinit var dir: Path
    private lateinit var dbPath: Path
    private lateinit var dao: DesktopWeatherDao
    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = Collections.synchronizedList(mutableListOf<FetchRequest>())
    private val printed = mutableListOf<String>()
    private var server: DaemonFetchServer? = null

    private val config = DesktopConfig(
        lat = 37.42, lon = -122.08, label = "Test", dateOffset = 0,
        settings = DesktopSettings(weatherSource = "NWS", visibleSources = listOf("NWS", "OPEN_METEO")),
    )

    @Before
    fun setUp() {
        // Short path: Unix socket paths are capped near 108 bytes.
        dir = Files.createTempDirectory(Path.of("/tmp"), "cli")
        dbPath = Files.createTempFile("weather_cli", ".db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(dbPath).apply { initialize() })
    }

    @After
    fun tearDown() {
        server?.close()
        daemonScope.cancel()
        dir.toFile().deleteRecursively()
        Files.deleteIfExists(dbPath)
    }

    private fun startDaemon(execute: suspend (FetchRequest) -> FetchResponse = { FetchResponse(ok = true) }) {
        server = DaemonFetchServer(dir, daemonScope, { tag, message, _ -> println("$tag $message") }) { request ->
            received += request
            execute(request)
        }.apply { start() }
        val socket = dir.resolve(FETCH_REQUEST_SOCKET)
        val deadline = System.currentTimeMillis() + 5_000
        while (!Files.exists(socket) && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun markFetchedNow(source: String) =
        dao.log(DesktopWeatherDao.FORECAST_FETCH_MARKER_TAG, DesktopWeatherDao.forecastFetchMarkerPrefix(source, config.lat, config.lon), "INFO")

    private fun cli(command: String, cfg: DesktopConfig? = config): Int = runBlocking {
        DesktopCli.run(command, cfg, dao, dir, System.currentTimeMillis()) { printed += it; println("cli> $it") }
    }

    @Test
    fun `refresh sends what Settings sends for the stale sources and exits 0`() {
        startDaemon()
        markFetchedNow("OPEN_METEO")

        assertEquals(DesktopCli.EXIT_OK, cli(DesktopCli.REFRESH))
        println("received: ${received.map { it.logLine() + " ids=" + it.sourceIds }}")
        assertEquals("only the stale displayed source; OPEN_METEO was fetched just now", 1, received.size)
        val request = received.single()
        assertEquals(FetchRequest.Kind.FORECAST, request.kind)
        assertEquals("NWS", request.sourceId)
        assertTrue("fills the observation window, as Settings does", request.observationWindow)
        assertEquals("cli", request.origin)
        assertEquals(listOf("FORECAST NWS ok"), printed)
    }

    @Test
    fun `refresh with every source fresh sends nothing`() {
        startDaemon()
        markFetchedNow("NWS")
        markFetchedNow("OPEN_METEO")

        assertEquals(DesktopCli.EXIT_OK, cli(DesktopCli.REFRESH))
        assertEquals(emptyList<FetchRequest>(), received.toList())
        assertTrue(printed.single(), printed.single().startsWith("all sources fresh"))
    }

    @Test
    fun `full-sync forces every source even when fresh`() {
        startDaemon()
        markFetchedNow("NWS")
        markFetchedNow("OPEN_METEO")

        assertEquals(DesktopCli.EXIT_OK, cli(DesktopCli.FULL_SYNC))
        assertEquals(listOf(FetchRequest.Kind.FORECAST, FetchRequest.Kind.OTHER_SOURCES), received.map { it.kind })
        assertEquals(listOf("FORECAST NWS ok", "OTHER_SOURCES all ok"), printed)
    }

    @Test
    fun `a failed fetch exits 1 and still sends the rest`() {
        startDaemon { request ->
            if (request.kind == FetchRequest.Kind.FORECAST) {
                FetchResponse(ok = false, errorClass = "HttpRequestTimeoutException", errorMessage = "timeout")
            } else {
                FetchResponse(ok = true)
            }
        }
        assertEquals(DesktopCli.EXIT_FETCH_FAILED, cli(DesktopCli.FULL_SYNC))
        assertEquals(2, received.size)
        assertTrue(printed.first(), printed.first().startsWith("FORECAST NWS failed: HttpRequestTimeoutException"))
    }

    @Test
    fun `no daemon exits 2 and no location exits 3`() {
        assertEquals(DesktopCli.EXIT_DAEMON_UNAVAILABLE, cli(DesktopCli.FULL_SYNC))
        assertTrue(printed.last(), printed.last().contains("daemon is not running"))
        assertEquals(DesktopCli.EXIT_NO_LOCATION, cli(DesktopCli.REFRESH, cfg = null))
    }
}
