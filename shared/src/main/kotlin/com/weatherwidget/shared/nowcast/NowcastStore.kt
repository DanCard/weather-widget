package com.weatherwidget.shared.nowcast

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.shared.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

private const val TAG = "Nowcast"

/**
 * The nowcast's only storage: one small JSON per site on disk, no Room/desktop table — the data is
 * dead within the hour, so rows would only be pruned. Sites are keyed at 2 decimals (~1 km, the
 * app's weather-site radius), finer than any 15-minute model cell (HRRR 3 km, ICON-D2 2 km).
 */
class NowcastStore(
    private val dir: File,
) {
    @Serializable
    private data class CoverageRecord(val native: Boolean, val checkedAtMs: Long)

    fun loadSeries(lat: Double, lon: Double, nowMs: Long): NowcastSeries? =
        read<NowcastSeries>(seriesFile(lat, lon))?.takeIf { nowMs - it.fetchedAtMs in 0 until MAX_SERIES_AGE_MS }

    /** An older series never replaces a newer one (a slow full fetch landing after a nowcast). */
    fun saveSeries(lat: Double, lon: Double, series: NowcastSeries) {
        val file = seriesFile(lat, lon)
        val existing = read<NowcastSeries>(file)
        if (existing != null && existing.fetchedAtMs > series.fetchedAtMs) return
        write(file, json.encodeToString(series))
        prune(SERIES_PREFIX, series.fetchedAtMs - MAX_SERIES_AGE_MS)
    }

    /** Null when never probed here or the answer is older than [NowcastCoverage.MAX_AGE_MS]. */
    fun loadCoverage(lat: Double, lon: Double, nowMs: Long): Boolean? =
        read<CoverageRecord>(coverageFile(lat, lon))
            ?.takeIf { nowMs - it.checkedAtMs in 0 until NowcastCoverage.MAX_AGE_MS }
            ?.native

    fun saveCoverage(lat: Double, lon: Double, native: Boolean, checkedAtMs: Long) {
        write(coverageFile(lat, lon), json.encodeToString(CoverageRecord(native, checkedAtMs)))
        prune(COVERAGE_PREFIX, checkedAtMs - NowcastCoverage.MAX_AGE_MS)
    }

    private inline fun <reified T> read(file: File): T? =
        runCatching { if (file.exists()) json.decodeFromString<T>(file.readText()) else null }
            .onFailure { Log.w(TAG, "NOWCAST_STORE read failed ${file.name}: ${it.message}") }
            .getOrNull()

    private fun write(file: File, text: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "NOWCAST_STORE write failed ${file.name}: ${it.message}") }
    }

    private fun prune(prefix: String, olderThanMs: Long) {
        dir.listFiles { f -> f.name.startsWith(prefix) && f.lastModified() < olderThanMs }?.forEach { it.delete() }
    }

    private fun seriesFile(lat: Double, lon: Double) = File(dir, SERIES_PREFIX + key(lat, lon) + ".json")
    private fun coverageFile(lat: Double, lon: Double) = File(dir, COVERAGE_PREFIX + key(lat, lon) + ".json")

    companion object {
        const val MAX_SERIES_AGE_MS = 3L * 60 * 60 * 1000
        private const val SERIES_PREFIX = "nowcast_"
        private const val COVERAGE_PREFIX = "nowcast-coverage_"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Quantized first, so the active location (37.4166) and a row's stored coordinate
         * (`LocationMatch.quantize` → 37.417) name the same file.
         */
        internal fun key(lat: Double, lon: Double): String =
            String.format(Locale.US, "%.2f_%.2f", LocationMatch.quantize(lat), LocationMatch.quantize(lon))
    }
}
