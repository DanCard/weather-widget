package com.weatherwidget.shared.rainmap

import com.weatherwidget.data.remote.weathernext.WeatherNextAvailability
import com.weatherwidget.data.remote.weathernext.WeatherNextDomain
import com.weatherwidget.data.remote.weathernext.WeatherNextForecastClient
import com.weatherwidget.data.remote.weathernext.WeatherNextFrame
import com.weatherwidget.data.remote.weathernext.WeatherNextFrameCache
import com.weatherwidget.data.remote.weathernext.WeatherNextGoneException
import com.weatherwidget.data.remote.weathernext.WeatherNextManifest
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * The WeatherNext "Sim loop" viewer's rules, both platforms: manifest load (with the day-long
 * "host gone" memory), a layer's frames, the frame prefetch, playback position and the log lines.
 * Android `RainForecastActivity` and desktop `RainForecastWindow` each wrote all of it; desktop's
 * copy let a layer switch mix two layers' frames and forgot "gone" on every restart
 * (plans/261024-shared-weathernext-loop-player.md). The platforms keep decoding, the UI, the ticker
 * that calls [advance], and the Open-Meteo hand-off.
 */
object WeatherNextLoop {
    const val TICK_MS = 35L

    /** One frame per 140 ms at 1× (~7 fps). */
    const val FRAME_STEP_MS = 140L

    val SPEEDS = listOf(0.5f, 1.0f, 1.5f, 2.5f)

    const val ERROR_TAG = "SIM_LOOP_ERROR"
    const val FALLBACK_TAG = "SIM_LOOP_FALLBACK"

    /** Playback position one [TICK_MS] later at [speed]; back to the first frame past the last. */
    fun advance(pos: Double, frameCount: Int, speed: Float): Double {
        if (frameCount <= 0) return 0.0
        val next = pos + (TICK_MS * speed) / FRAME_STEP_MS.toFloat()
        return if (next > frameCount - 1.0) 0.0 else next
    }

    /** The previous / next button: one whole frame from the current one, held at the ends. */
    fun step(pos: Double, frameCount: Int, delta: Int): Double =
        if (delta < 0) max(0.0, pos.toInt() - 1.0) else min(frameCount - 1.0, pos.toInt() + 1.0)

    fun framesFor(manifest: WeatherNextManifest, layerId: String): List<WeatherNextFrame> =
        manifest.frames.filter { it.files.containsKey(layerId) }

    /** When the host last answered 404/410, and which ([WeatherNextAvailability.knownGone]). */
    interface GoneStore {
        val goneAtMs: Long?
        val goneStatus: Int

        fun markGone(status: Int, nowMs: Long)

        fun clear()
    }

    sealed interface ManifestOutcome {
        data class Loaded(val manifest: WeatherNextManifest) : ManifestOutcome

        data class Failed(val error: RainLoopLoadError) : ManifestOutcome
    }

    /**
     * The manifest, or why not. A 404/410 within the last day still stands, so the viewer says so
     * without asking the host again; a fresh 404/410 starts that day, a success ends it.
     */
    suspend fun loadManifest(
        client: WeatherNextForecastClient,
        domain: WeatherNextDomain,
        store: GoneStore,
        nowMs: Long,
    ): ManifestOutcome {
        if (WeatherNextAvailability.knownGone(store.goneAtMs, nowMs)) {
            return ManifestOutcome.Failed(RainLoopLoadError.gone(WeatherNextAvailability.HOST, store.goneStatus))
        }
        return client.fetchManifest(domain).fold(
            onSuccess = {
                store.clear()
                ManifestOutcome.Loaded(it)
            },
            onFailure = {
                if (it is WeatherNextGoneException) store.markGone(it.status, nowMs)
                ManifestOutcome.Failed(WeatherNextAvailability.loadError(it))
            },
        )
    }

    /**
     * Fetches and decodes [frames] of [layerId] in order, handing each to [onFrame]. Stops as soon as
     * [stillWanted] is false (the viewer switched layer): a decode that finished after that goes to
     * [onDiscard], never to [onFrame], so one layer's frames never land in another's slots. Frames
     * that fail are skipped — the loop plays the rest; none at all is [RainLoopLoadError.noFrames].
     * Null when something loaded or the run was abandoned.
     */
    suspend fun <T : Any> prefetch(
        client: WeatherNextForecastClient,
        cacheDir: File,
        domain: WeatherNextDomain,
        layerId: String,
        manifest: WeatherNextManifest,
        frames: List<WeatherNextFrame>,
        stillWanted: () -> Boolean,
        decode: (File) -> T?,
        onFrame: suspend (index: Int, image: T) -> Unit,
        onDiscard: (T) -> Unit = {},
    ): RainLoopLoadError? {
        WeatherNextFrameCache.pruneOtherRuns(cacheDir, domain, layerId, manifest)
        var loaded = 0
        for ((index, frame) in frames.withIndex()) {
            if (!stillWanted()) return null
            val file = client.cachedFrame(cacheDir, domain, layerId, manifest, frame).getOrNull() ?: continue
            val image = try {
                decode(file)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            if (!stillWanted()) {
                onDiscard(image)
                return null
            }
            loaded++
            onFrame(index, image)
        }
        return if (loaded == 0 && stillWanted()) RainLoopLoadError.noFrames(WeatherNextAvailability.HOST, frames.size) else null
    }

    fun errorLine(error: RainLoopLoadError, domain: WeatherNextDomain): String =
        "${error.logFields()} domain=${domain.id}"

    fun fallbackLine(error: RainLoopLoadError): String = "from=weathernext kind=${error.kind} to=open_meteo"
}
