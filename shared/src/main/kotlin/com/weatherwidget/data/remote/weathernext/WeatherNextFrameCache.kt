package com.weatherwidget.data.remote.weathernext

import java.io.File

/**
 * File names for cached WeatherNext loop frames, **keyed by model run**.
 *
 * The first version (2026-10-20) named frames `${domain}_${layer}_${lead}.jpg` and reused any such
 * file, so once a lead hour was on disk a newer run was never downloaded: the loop replayed the
 * first forecast it ever fetched while its header showed the current run time. Both viewers
 * (Android and desktop) had the same check. See
 * `plans/261020-sim-loop-pill-free-space-placement-and-review-fixes.md`.
 */
object WeatherNextFrameCache {
    private const val EXTENSION = ".jpg"

    /**
     * The model run a frame belongs to: the manifest's `run`, else its `init`, else the frame's own
     * `init`, else a hash of the frame's file path. Never empty — a missing run must not quietly fall
     * back to a name that older runs share.
     */
    fun runKey(manifest: WeatherNextManifest, frame: WeatherNextFrame, layerId: String): String {
        val raw = listOf(manifest.run, manifest.init, frame.init).firstOrNull { !it.isNullOrBlank() }
            ?: "p" + Integer.toHexString((frame.files[layerId] ?: frame.valid).hashCode())
        return sanitize(raw)
    }

    fun fileName(domain: WeatherNextDomain, layerId: String, manifest: WeatherNextManifest, frame: WeatherNextFrame): String =
        "${prefix(domain, layerId)}${runKey(manifest, frame, layerId)}_${String.format("%03d", frame.lead)}$EXTENSION"

    /**
     * Deletes [domain]/[layerId] frames that do not belong to [manifest]'s runs, plus any leftover
     * partial downloads. Returns how many files went.
     */
    fun pruneOtherRuns(cacheDir: File, domain: WeatherNextDomain, layerId: String, manifest: WeatherNextManifest): Int {
        val keep = manifest.frames.filter { it.files.containsKey(layerId) }
            .map { fileName(domain, layerId, manifest, it) }
            .toSet()
        val prefix = prefix(domain, layerId)
        val stale = cacheDir.listFiles()?.filter {
            it.name.startsWith(prefix) && (it.name.endsWith(".part") || (it.name.endsWith(EXTENSION) && it.name !in keep))
        }.orEmpty()
        return stale.count { it.delete() }
    }

    private fun prefix(domain: WeatherNextDomain, layerId: String) = "${domain.id}_${layerId}_"

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9-]"), "")
}
