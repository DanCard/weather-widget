package com.weatherwidget.desktop

import com.weatherwidget.shared.util.Log
import java.util.concurrent.TimeUnit

/**
 * Best-effort detection of whether the display is powered on, used to gate the non-primary actuals
 * fetch loop (don't poll other sources while the user isn't looking at the screen).
 *
 * In the project's fire-and-forget shell-out style (cf. `notify-send` / `gdbus` in
 * [DesktopProcess]). A **locked session is off**, whatever the monitor says (2026-10-19: a workstation
 * locked overnight with its monitor never powered down read as on all night); otherwise X11 DPMS via
 * `xset -q`, else logind's lock state. [screenState] reports null when neither tool answers;
 * [isScreenOn] then **fails open** (true) — a missing probe must never silently freeze data — while
 * the away rule treats it as unknown ([com.weatherwidget.shared.util.UserPresence]).
 */
object ScreenStateDetector {
    private const val TAG = "ScreenStateDetector"
    private const val PROBE_TIMEOUT_SECONDS = 3L

    @Volatile private var failOpenWarned = false

    /** True if the display appears on and unlocked. Best-effort; fail-open (true) when undetectable. */
    fun isScreenOn(): Boolean {
        screenState()?.let { return it }
        if (!failOpenWarned) {
            failOpenWarned = true
            Log.w(TAG, "Screen-state probe unavailable (xset/loginctl) — assuming screen on (fail-open).")
        }
        return true
    }

    /** True when on and unlocked, false when off or locked, null when neither probe answers. */
    fun screenState(): Boolean? = combine(
        unlocked = parseLoginctlLocked(lockedHintOutput()),
        monitorOn = parseXsetMonitorState(runCommand("xset", "-q")),
    )

    /** Pure: a lock wins ([unlocked] false → off); else the monitor; else the lock state; else unknown. */
    fun combine(unlocked: Boolean?, monitorOn: Boolean?): Boolean? = when {
        unlocked == false -> false
        monitorOn != null -> monitorOn
        else -> unlocked
    }

    /**
     * `LockedHint` of this session: `self` when the daemon runs inside it (autostart), else the
     * user's display session (`loginctl show-user … -p Display`), as from a shell outside it.
     */
    private fun lockedHintOutput(): String? =
        runCommand("loginctl", "show-session", "self", "-p", "LockedHint")
            ?: runCommand("loginctl", "show-user", System.getProperty("user.name") ?: "", "-p", "Display", "--value")
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCommand("loginctl", "show-session", it, "-p", "LockedHint") }

    /**
     * Pure parser for `xset -q` output. Returns true when the monitor is on, false when it is
     * Off/Standby/Suspend, and null when the state line is absent (so the caller can fall back).
     */
    fun parseXsetMonitorState(xsetOutput: String?): Boolean? {
        if (xsetOutput == null) return null
        val line = xsetOutput.lineSequence().firstOrNull { it.contains("Monitor is") } ?: return null
        return when {
            line.contains("Monitor is On") -> true
            line.contains("Off") || line.contains("Standby") || line.contains("Suspend") -> false
            else -> null
        }
    }

    /**
     * Pure parser for `loginctl ... -p LockedHint` output ("LockedHint=no"). On = not locked.
     * Null when the property is absent.
     */
    fun parseLoginctlLocked(loginctlOutput: String?): Boolean? {
        if (loginctlOutput == null) return null
        val line = loginctlOutput.lineSequence().firstOrNull { it.startsWith("LockedHint=") } ?: return null
        return when (line.substringAfter("=").trim()) {
            "yes" -> false
            "no" -> true
            else -> null
        }
    }

    /** Runs a short-lived command, returning stdout, or null on any failure/timeout. */
    private fun runCommand(vararg command: String): String? = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() == 0) {
            output
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
