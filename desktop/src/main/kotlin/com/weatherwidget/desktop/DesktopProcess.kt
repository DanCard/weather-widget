package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.shared.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

const val QUIT_TRIGGER = ".quit"
const val QUIT_PREFIX = ".quit-"
const val SHOW_TRIGGER = ".show"
const val UI_SHOW_TRIGGER = ".ui-show"
const val CONFIG_CHANGED_TRIGGER = ".config-changed"

val appLaunchId: String = UUID.randomUUID().toString()

fun appDataDir(): Path = DesktopDbPaths.defaultDbPath().parent

const val FRESHNESS_THRESHOLD_MS = 10 * 60 * 1000L
const val CURRENT_TEMP_UI_INTERVAL_MS = 2 * 60 * 1000L
const val SUSPEND_RECHECK_INTERVAL_MS = 5 * 60 * 1000L

enum class LaunchRefreshAction {
    FULL_FORECAST,
    OBSERVATIONS,
    NONE,
}

fun determineLaunchRefreshAction(
    cachePresent: Boolean,
    lastObservationFetchMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): LaunchRefreshAction {
    if (!cachePresent) return LaunchRefreshAction.FULL_FORECAST
    val observationsAreFresh = lastObservationFetchMs != null &&
        (nowMs - lastObservationFetchMs) < FRESHNESS_THRESHOLD_MS
    return if (observationsAreFresh) LaunchRefreshAction.NONE else LaunchRefreshAction.OBSERVATIONS
}

fun isPackaged(): Boolean = System.getProperty("jpackage.app-path") != null

const val MIN_REFRESH_DELAY_MS = 10 * 60 * 1000L
const val DEFAULT_REFRESH_DELAY_MS = 15 * 60 * 1000L

fun computeRefreshDelayMs(hourly: List<com.weatherwidget.data.model.HourlyForecast>?): Long {
    if (hourly.isNullOrEmpty()) return DEFAULT_REFRESH_DELAY_MS
    val updatesPerHour = com.weatherwidget.shared.util.TemperatureInterpolator.getUpdatesPerHour(hourly)
    val intervalMs = (3600_000L / updatesPerHour).coerceAtLeast(MIN_REFRESH_DELAY_MS)
    return intervalMs
}

class DesktopClients {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    fun close() {
        httpClient.close()
    }
}


fun signalIncumbentToQuit(dir: Path, launchId: String) {
    Files.createDirectories(dir)
    if (Files.exists(dir)) {
        Files.list(dir).use { paths ->
            paths.forEach { path ->
                val name = path.fileName.toString()
                if (name == QUIT_TRIGGER || name.startsWith(QUIT_PREFIX)) {
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }
    val trigger = dir.resolve("$QUIT_PREFIX$launchId")
    Files.writeString(
        trigger,
        "",
        java.nio.charset.StandardCharsets.UTF_8
    )
}

fun maybePackagedSetup() {
    if (!isPackaged()) return
    runCatching { extractGenmonScript() }.onFailure { System.err.println("genmon extract failed: $it") }
}

fun extractGenmonScript() {
    val target = appDataDir().resolve("genmon-weather.py")
    if (Files.exists(target)) return
    val stream = object {}.javaClass.getResourceAsStream("/scripts/genmon-weather.py") ?: return
    Files.createDirectories(target.parent)
    stream.use { Files.copy(it, target) }
    target.toFile().setExecutable(true)
}

fun launchUiProcess(): Process {
    val command = mutableListOf<String>()
    if (isPackaged()) {
        val appPath = System.getProperty("jpackage.app-path") ?: throw IllegalStateException("jpackage.app-path not set in packaged mode")
        command.add(appPath)
        command.add("ui")
    } else {
        val javaCmd = ProcessHandle.current().info().command().orElse("java")
        val cp = System.getProperty("java.class.path") ?: ""
        command.add(javaCmd)
        command.add("-cp")
        command.add(cp)
        command.add("com.weatherwidget.desktop.MainKt")
        command.add("ui")
    }
    Log.i("DesktopProcess", "Launching UI process: $command")
    val pb = ProcessBuilder(command)
    
    val env = pb.environment()
    val display = env["DISPLAY"]
    val xauth = env["XAUTHORITY"]
    val home = env["HOME"]
    val path = env["PATH"]
    val ldLibrary = env["LD_LIBRARY_PATH"]
    
    env.clear()
    if (display != null) env["DISPLAY"] = display
    if (xauth != null) env["XAUTHORITY"] = xauth
    if (home != null) env["HOME"] = home
    if (path != null) env["PATH"] = path
    if (ldLibrary != null) env["LD_LIBRARY_PATH"] = ldLibrary
    
    return pb.inheritIO().start()
}
