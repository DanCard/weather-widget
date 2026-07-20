package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.shared.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

const val QUIT_TRIGGER = ".quit"
const val QUIT_PREFIX = ".quit-"
const val SHOW_TRIGGER = ".show"
const val UI_SHOW_TRIGGER = ".ui-show"
const val CONFIG_CHANGED_TRIGGER = ".config-changed"
// Direction matters for the data triggers: each one has a single producer and a single consumer,
// so neither process ever reacts to its own write (a self-echo here once clobbered the daemon's
// Stale status back to Live right after every failed fetch).
// Daemon → UI: "the DB/cache changed (fetch success OR failure) — reload and re-read status".
const val DATA_UPDATED_TRIGGER = ".data-updated"
// UI → daemon: "I ran a successful refresh() against the DB myself — pick it up".
const val REFRESH_REQUESTED_TRIGGER = ".refresh-requested"

val appLaunchId: String = UUID.randomUUID().toString()

fun appDataDir(): Path = DesktopDbPaths.defaultDbPath().parent

private fun touchTrigger(name: String) {
    runCatching {
        Files.writeString(appDataDir().resolve(name), "", java.nio.charset.StandardCharsets.UTF_8)
    }
}

// Set by the daemon once its UI notify socket is listening. `notifyDataUpdated()` pushes over it in
// addition to touching the file trigger, so the UI gets a reliable (non-lossy) signal while the file
// stays as a fallback. Null in the UI process and in tests, where the push is simply skipped.
@Volatile
var uiNotifyServerRef: UiNotifyServer? = null

fun notifyDataUpdated() {
    touchTrigger(DATA_UPDATED_TRIGGER)
    runCatching { uiNotifyServerRef?.pushDataUpdated() }
}

fun notifyRefreshRequested() = touchTrigger(REFRESH_REQUESTED_TRIGGER)

const val FRESHNESS_THRESHOLD_MS = 10 * 60 * 1000L
// A launch must re-pull the forecast once the cached forecast is older than this. Aligns with the
// AC active-source forecast interval (DesktopFetchStrategy.AC_ACTIVE_FORECAST_MINUTES). Without this,
// a daemon that restarts more often than the periodic forecast loop's first delay (which waits a full
// interval before its first fetch) would refresh observations forever but never re-pull the forecast,
// freezing the multi-day daily columns at whatever coverage the last full fetch had.
const val FORECAST_FRESHNESS_THRESHOLD_MS = 60 * 60 * 1000L
// UI-process safety-net cache reload. The `.data-updated` trigger is the primary (interrupt-driven)
// update path; this slow poll only exists so a missed watch event or a dead watcher loop degrades
// to "up to 10 minutes stale" instead of "stale forever".
const val UI_FALLBACK_RELOAD_MS = 10 * 60 * 1000L
// The fallback loop ticks at this short cadence rather than sleeping one long [UI_FALLBACK_RELOAD_MS]
// delay(): coroutine delay() runs on the monotonic clock and freezes during suspend, so a single long
// sleep would not fire promptly on wake. A short tick fires right after resume and also lets the loop
// notice a suspend-sized wall-clock jump (isSuspendJump) and reload immediately — bounding post-wake
// staleness to one tick instead of "until the frozen 10-minute timer eventually elapses".
const val UI_FALLBACK_TICK_MS = 30 * 1000L
const val SUSPEND_RECHECK_INTERVAL_MS = 5 * 60 * 1000L

// Resume-from-suspend detection. The fetch loops sleep on coroutine `delay()`, which schedules
// against CLOCK_MONOTONIC and freezes during suspend, so they do NOT fire on wake. We detect resume
// and kick one catch-up refresh. Two detectors race; [RESUME_DEBOUNCE_MS] collapses them
// into a single fetch per wake.
const val HEARTBEAT_INTERVAL_MS = 30_000L
// A heartbeat tick whose wall-clock gap exceeds interval + slack (~90s) means we were suspended.
// Mirrors ~/bin/sys-logging.sh's RESUME_DETECT_THRESHOLD_MS (75s) time-jump heuristic.
const val SUSPEND_JUMP_SLACK_MS = 60_000L
const val RESUME_DEBOUNCE_MS = 2 * 60 * 1000L

// Hold-off before the resume kick's catch-up fetch. Right after wake the network stack is still
// coming up (DNS fails with UnresolvedAddressException for the first ~10s), and every network
// client on the machine re-fetches the instant the link returns — a weather refresh is low
// priority, so sit out that thundering herd instead of joining it. The jitter de-syncs us from
// other fixed-delay clients. If NetworkManager confirms connectivity during the pause,
// kickNetworkRestoredRefresh cancels this job and takes over with its own (shorter) pause, so the
// hold-off never delays a network that is already known up. Delay + jitter must stay well under
// [RESUME_DEBOUNCE_MS] so a kick finishes its pause before the next one is accepted.
const val RESUME_KICK_DELAY_MS = 15_000L
const val RESUME_KICK_JITTER_MS = 10_000L

enum class LaunchRefreshAction {
    FULL_FORECAST,
    OBSERVATIONS,
    NONE,
}

fun determineLaunchRefreshAction(
    cachePresent: Boolean,
    lastObservationFetchMs: Long?,
    lastForecastFetchMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): LaunchRefreshAction {
    if (!cachePresent) return LaunchRefreshAction.FULL_FORECAST
    // A stale forecast takes priority: a full forecast fetch also refreshes observations, so it
    // supersedes an observations-only refresh.
    val forecastIsStale = lastForecastFetchMs == null ||
        (nowMs - lastForecastFetchMs) >= FORECAST_FRESHNESS_THRESHOLD_MS
    if (forecastIsStale) return LaunchRefreshAction.FULL_FORECAST
    val observationsAreFresh = lastObservationFetchMs != null &&
        (nowMs - lastObservationFetchMs) < FRESHNESS_THRESHOLD_MS
    return if (observationsAreFresh) LaunchRefreshAction.NONE else LaunchRefreshAction.OBSERVATIONS
}

/**
 * True when a logind `PrepareForSleep` D-Bus signal line indicates the machine is *waking*
 * (`false` = resuming; `true` = about to sleep). Matches `gdbus monitor` output lines such as
 * `/org/freedesktop/login1: org.freedesktop.login1.Manager.PrepareForSleep (false)`.
 */
fun isResumeSignalLine(line: String): Boolean =
    line.contains("PrepareForSleep") && line.contains("false")

/**
 * True when a heartbeat tick's wall-clock gap is far larger than the scheduled interval, which only
 * happens when the process was frozen by a system suspend (coroutine `delay()` runs on the monotonic
 * clock and cannot itself produce such a jump).
 */
fun isSuspendJump(expectedElapsedMs: Long, actualElapsedMs: Long, slackMs: Long): Boolean =
    actualElapsedMs > expectedElapsedMs + slackMs

// Network-restored detection. A catch-up fetch kicked right after resume/login often races the
// network stack and fails with a DNS error; a failed fetch never updates lastSuccessfulFetch, so
// re-running the launch refresh once connectivity returns re-fetches exactly what is still stale.
// Primary recovery is a gdbus NetworkManager monitor (interrupt-driven, mirrors the logind resume
// detector); [OFFLINE_RETRY_DELAYS_MS] is only belt-and-suspenders for a dead gdbus stream or NM
// reporting connected before DNS actually resolves.
const val NETWORK_RESTORE_DEBOUNCE_MS = 30_000L
val OFFLINE_RETRY_DELAYS_MS = listOf(5_000L, 15_000L)

// Deliberate pause before the network-restored kick's first fetch: a weather refresh is low
// priority, so don't join the thundering herd of every client re-fetching the instant the link
// comes up. The jitter de-syncs us from other fixed-delay clients. Delay + jitter must stay well
// under NETWORK_RESTORE_DEBOUNCE_MS so a kick finishes its pause before the next one is accepted.
const val NETWORK_RESTORE_KICK_DELAY_MS = 3_000L
const val NETWORK_RESTORE_KICK_JITTER_MS = 2_000L

// How long after a wake/network event an offline-classified current-temp failure is presented as
// "waiting for network to warm up" instead of a hard error. Sized to cover the resume hold-off
// (≤25s) + the offline retry backoff (5s+15s) + the network-restored kick pause (≤5s), with slack.
const val NETWORK_WARMUP_GRACE_MS = 90_000L

/**
 * True while a failed fetch should be blamed on post-wake network warm-up rather than surfaced as
 * an error: [lastWakeEventMs] (the newest RESUME_DETECT / NETWORK_DETECT row) is within
 * [graceMs] of now. Anchored to the wake event, NOT the failure row — a genuinely dead network
 * keeps producing *fresh* failures every fetch cycle, which must escalate to the real error banner
 * once the grace window after the last wake has passed.
 */
fun isNetworkWarmupWindow(
    lastWakeEventMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
    graceMs: Long = NETWORK_WARMUP_GRACE_MS,
): Boolean = lastWakeEventMs != null && (nowMs - lastWakeEventMs) in 0 until graceMs

/**
 * Delay before offline-failure retry [attempt] (0-based), or null when the failure is not
 * offline-classified or the schedule is exhausted (normal final-failure handling applies).
 */
fun offlineRetryDelayMs(attempt: Int, isOffline: Boolean): Long? =
    if (isOffline) OFFLINE_RETRY_DELAYS_MS.getOrNull(attempt) else null

/**
 * True when a NetworkManager D-Bus signal line indicates full connectivity was (re)gained.
 * Matches `gdbus monitor` output such as
 * `/org/freedesktop/NetworkManager: org.freedesktop.NetworkManager.StateChanged (uint32 70,)`
 * (NM_STATE_CONNECTED_GLOBAL) and `PropertiesChanged` lines containing `'Connectivity': <uint32 4>`
 * (NM_CONNECTIVITY_FULL). State 70 requires a non-digit follower so a larger number cannot match.
 */
fun isNetworkRestoredSignalLine(line: String): Boolean {
    if (line.contains("'Connectivity': <uint32 4>")) return true
    if (!line.contains("StateChanged")) return false
    val marker = "uint32 70"
    val idx = line.indexOf(marker)
    if (idx < 0) return false
    return line.getOrNull(idx + marker.length)?.isDigit() != true
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
        install(HttpRequestRetry) {
            maxRetries = 2
            retryOnExceptionIf { _, cause ->
                cause is io.ktor.client.network.sockets.ConnectTimeoutException ||
                cause is io.ktor.client.network.sockets.SocketTimeoutException ||
                cause is io.ktor.client.plugins.HttpRequestTimeoutException ||
                cause is java.io.IOException
            }
            exponentialDelay(base = 2.0, maxDelayMs = 2_000)
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

/** How often a daemon actively re-checks whether a newer instance has superseded it. */
const val INSTANCE_RECHECK_INTERVAL_MS = 30_000L

/**
 * True if the app dir contains a `.quit-<launchId>` signal from a *different* (newer) launch than
 * [myLaunchId]. The newest launcher's `signalIncumbentToQuit` leaves exactly its own token in the
 * dir, so an older daemon that missed the WatchService event can still discover it by polling this.
 * Best-effort safety net for the lossy file-watch interrupt — not a replacement for it.
 */
fun supersededByNewerInstance(dir: Path, myLaunchId: String): Boolean {
    if (!Files.exists(dir)) return false
    return runCatching {
        Files.list(dir).use { paths ->
            paths.anyMatch { path ->
                val name = path.fileName.toString()
                name.startsWith(QUIT_PREFIX) && name.substring(QUIT_PREFIX.length) != myLaunchId
            }
        }
    }.getOrDefault(false)
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

    // Start from a clean, minimal environment for the GUI child, then forward only what it needs.
    // CRITICAL: XDG_CONFIG_HOME / XDG_DATA_HOME must be carried over — the app derives config.json
    // (DesktopConfig) and weather.db + the trigger files + weather.sock (DesktopDbPaths) from them.
    // If they were set for the daemon but dropped here, the UI process would resolve a *different*
    // data dir and silently stop sharing state with the daemon (wrong DB, .ui-show never arrives).
    val env = pb.environment()
    val passthrough = listOf(
        "DISPLAY", "XAUTHORITY",            // X11 connection
        "HOME", "PATH", "LD_LIBRARY_PATH",  // process basics + native libs
        "XDG_CONFIG_HOME", "XDG_DATA_HOME", // app config/data dirs (where daemon & UI rendezvous)
        "XDG_RUNTIME_DIR",                  // session runtime dir (GUI/portals)
        "DBUS_SESSION_BUS_ADDRESS",         // desktop session bus (GTK/portals)
    )
    val preserved = passthrough.mapNotNull { key -> env[key]?.let { key to it } }
    env.clear()
    preserved.forEach { (key, value) -> env[key] = value }

    return pb.inheritIO().start()
}

/**
 * The absolute path [launchUiProcess] would exec (the command's first element), or null when it
 * cannot be determined. Used to detect that the running distributable was deleted out from under a
 * live daemon: the daemon keeps running on its now-deleted inode (still serving the panel socket and
 * fetching data) but can no longer `posix_spawn` the UI window — every genmon click then fails
 * silently with "No such file or directory".
 */
fun uiLaunchExecutablePath(): String? = runCatching {
    if (isPackaged()) System.getProperty("jpackage.app-path")
    else ProcessHandle.current().info().command().orElse(null)
}.getOrNull()

/** True when the UI launcher binary is gone (e.g. a `clean`/rebuild removed the distributable). */
fun isUiLauncherMissing(): Boolean {
    val path = uiLaunchExecutablePath() ?: return false // unknown → don't cry wolf
    return !Files.exists(Path.of(path))
}

/**
 * Best-effort desktop notification via `notify-send` (XFCE's notifyd). Fire-and-forget: never throws
 * and is a no-op when `notify-send` is absent, matching the project's best-effort/interrupt-driven
 * preference — a missing notifier must never take down the daemon.
 */
fun notifyDesktop(summary: String, body: String, urgency: String = "normal") {
    runCatching {
        ProcessBuilder("notify-send", "-u", urgency, "-a", "Weather Widget", summary, body).start()
    }.onFailure { Log.w("DesktopProcess", "notify-send failed: ${it.message}") }
}
