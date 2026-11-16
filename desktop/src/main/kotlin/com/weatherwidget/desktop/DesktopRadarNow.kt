package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.RadarNowSample
import com.weatherwidget.shared.observations.RadarNowSampler
import com.weatherwidget.shared.util.Log
import java.io.File

/**
 * Desktop's [RadarNowSampler.Store]: the last radar sample in one small file, because the daemon
 * samples and the UI process draws the header (plans/261115-header-rain-now-dots.md). Android keeps
 * it in SharedPreferences.
 */
class FileRadarNowStore(private val file: File) : RadarNowSampler.Store {
    override fun read(): RadarNowSample? =
        runCatching { if (file.exists()) RadarNowSample.decode(file.readText().trim()) else null }
            .onFailure { Log.w("RADAR_NOW_FAIL", "store read ${file.path}: ${it.message}") }
            .getOrNull()

    override fun write(sample: RadarNowSample) {
        runCatching {
            file.parentFile?.mkdirs()
            // Write then rename: the UI process may read while the daemon writes.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(sample.encode())
            if (!tmp.renameTo(file)) {
                file.writeText(sample.encode())
                tmp.delete()
            }
        }.onFailure { Log.w("RADAR_NOW_FAIL", "store write ${file.path}: ${it.message}") }
    }
}

object DesktopRadarNow {
    val store: RadarNowSampler.Store by lazy {
        val cacheHome = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.cache"
        FileRadarNowStore(File(cacheHome, "weather-widget/radar-now.txt"))
    }
}
