package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Command-line hooks, the desktop analogue of Android's debug adb hooks (`arch/operations.md`):
 * `weather-widget-desktop refresh` (Settings → Refresh data: stale sources only) and
 * `weather-widget-desktop full-sync` (every usable source, forced, billed). Each request goes to the
 * running daemon over its fetch socket ([DaemonFetchClient]), which stores the rows and repaints the
 * window itself; this process never starts a daemon or a window
 * (plans/261124-desktop-cli-refresh-hook.md).
 */
internal object DesktopCli {
    const val REFRESH = "refresh"
    const val FULL_SYNC = "full-sync"
    val COMMANDS = setOf(REFRESH, FULL_SYNC)

    const val EXIT_OK = 0
    const val EXIT_FETCH_FAILED = 1
    const val EXIT_DAEMON_UNAVAILABLE = 2
    const val EXIT_NO_LOCATION = 3

    const val ORIGIN = "cli"
    const val LOG_TAG = "CLI_REFRESH"

    /**
     * Runs [command] against the daemon listening in [appDir]. [config] null = no location configured.
     * Prints one line per request to [out]; returns the exit code.
     */
    suspend fun run(
        command: String,
        config: DesktopConfig?,
        dao: DesktopWeatherDao,
        appDir: Path,
        nowMs: Long,
        out: (String) -> Unit,
    ): Int {
        if (config == null) {
            out("no location configured")
            dao.log(LOG_TAG, "cmd=$command result=no_location", "WARN")
            return EXIT_NO_LOCATION
        }
        val requests = when (command) {
            REFRESH -> {
                val partition = desktopSettingsRefreshPartition(dao, config, { null }, nowMs)
                dao.log(com.weatherwidget.shared.util.SettingsRefreshAllSources.TAG, "origin=$ORIGIN ${partition.logLine()}", "INFO")
                DesktopRefreshRequests.settingsRefresh(config, partition, ORIGIN)
            }
            FULL_SYNC -> DesktopRefreshRequests.fullSync(config, ORIGIN)
            else -> error("unknown command $command")
        }
        if (requests.isEmpty()) {
            out("all sources fresh (fetched in the last 10 minutes); nothing to do")
            dao.log(LOG_TAG, "cmd=$command requests=0 result=all_fresh", "INFO")
            return EXIT_OK
        }
        val client = DaemonFetchClient(appDir)
        var exit = EXIT_OK
        for (request in requests) {
            val what = describe(request)
            val line = try {
                client.send(request)
                "$what ok"
            } catch (e: CancellationException) {
                throw e
            } catch (e: DaemonFetchException) {
                if (e.errorClass == DaemonFetchClient.DAEMON_UNAVAILABLE) {
                    out("$what failed: the daemon is not running (start weather-widget-desktop first)")
                    dao.log(LOG_TAG, "cmd=$command result=daemon_unavailable", "WARN")
                    return EXIT_DAEMON_UNAVAILABLE
                }
                exit = EXIT_FETCH_FAILED
                "$what failed: ${e.message}"
            }
            out(line)
        }
        dao.log(LOG_TAG, "cmd=$command requests=${requests.size} ok=${exit == EXIT_OK}", if (exit == EXIT_OK) "INFO" else "WARN")
        return exit
    }

    private fun describe(request: FetchRequest): String = when (request.kind) {
        FetchRequest.Kind.OTHER_SOURCES ->
            "OTHER_SOURCES ${request.sourceIds?.joinToString(",") ?: "all"}"
        else -> "${request.kind} ${request.sourceId}"
    }
}

/** `main`'s entry for [DesktopCli]: the user's config, database and daemon. Returns the exit code. */
internal fun runDesktopCli(command: String): Int {
    val dao = DesktopWeatherDao(DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() })
    return runBlocking {
        DesktopCli.run(command, DesktopConfigStore().load(), dao, appDataDir(), System.currentTimeMillis(), ::println)
    }
}
