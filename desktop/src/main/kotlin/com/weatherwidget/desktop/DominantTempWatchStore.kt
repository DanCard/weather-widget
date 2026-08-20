package com.weatherwidget.desktop

import com.weatherwidget.shared.notify.DominantTempWatchState
import com.weatherwidget.shared.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persistence for the one-shot dominant-station temperature watch, in its own file rather than in
 * [DesktopConfig].
 *
 * **Why not `DesktopConfig.settings`:** the watch is armed by the UI process and cleared by the
 * daemon when it fires, and the UI does *not* watch `config.json` for external edits. A daemon write
 * would therefore leave the UI holding a stale config whose next auto-save — triggered by nothing
 * more than opening Settings — would resurrect the armed flag and re-fire the alert. A separate
 * single-purpose file has no such clobber path: the UI only ever writes `armed = true`, the daemon
 * only ever writes `armed = false`.
 *
 * The flag and the baseline live together because arming must drop the baseline (see [setArmed]).
 *
 * Every operation is best-effort in the project's established style: a notification preference must
 * never take down a fetch loop or a settings screen.
 */
class DominantTempWatchStore(
    private val path: Path = appDataDir().resolve(FILE_NAME),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
    @Serializable
    private data class Persisted(
        val armed: Boolean = false,
        val baselineStationId: String? = null,
        val baselineTempF: Float? = null,
    )

    /** A missing or unreadable file reads as disarmed — the safe direction: nothing fires. */
    fun load(): DominantTempWatchState {
        if (!path.exists()) return DominantTempWatchState.DISARMED
        return runCatching {
            val p = json.decodeFromString(Persisted.serializer(), path.readText())
            DominantTempWatchState(
                armed = p.armed,
                baselineStationId = p.baselineStationId,
                baselineTempF = p.baselineTempF?.takeIf { it.isFinite() },
            )
        }.getOrElse {
            Log.w(TAG, "Unreadable watch state at $path (${it.message}); treating as disarmed.")
            DominantTempWatchState.DISARMED
        }
    }

    fun save(state: DominantTempWatchState) {
        runCatching {
            path.parent?.createDirectories()
            path.writeText(
                json.encodeToString(
                    Persisted.serializer(),
                    Persisted(
                        armed = state.armed,
                        baselineStationId = state.baselineStationId,
                        baselineTempF = state.baselineTempF?.takeIf { it.isFinite() },
                    ),
                ),
            )
        }.onFailure { Log.w(TAG, "Failed to persist watch state to $path: ${it.message}") }
    }

    fun isArmed(): Boolean = load().armed

    /**
     * Arms or disarms, always dropping the baseline, so "changed" is measured from the moment the
     * user asked rather than from whatever a previous watch left behind.
     */
    fun setArmed(armed: Boolean) {
        save(DominantTempWatchState(armed = armed))
    }

    companion object {
        const val FILE_NAME = "dominant-temp-watch.json"
        private const val TAG = "DominantTempWatchStore"
    }
}
