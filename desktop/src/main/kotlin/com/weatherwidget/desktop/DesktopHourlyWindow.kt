package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.HourlyWindowPolicy

/**
 * Which hourly window a routine fetch of [weatherSource] at the site takes: [HourlyWindowPolicy] with
 * the machine's power state, and the last FULL fetch read back from `app_logs`.
 *
 * The marker is a `HOURLY_FULL_FETCH` log row (message `source=… site=lat,lon`): app_logs keeps 72 h,
 * longer than the 24 h between FULL fetches, so no new table is needed. The service (what to ask a
 * billed source for) and the repository (what to store) both read it before the fetch writes it, so
 * they agree.
 */
internal class DesktopHourlyWindow(
    private val weatherDao: DesktopWeatherDao?,
    private val weatherSource: String,
    latitude: Double,
    longitude: Double,
    private val powerState: () -> PowerDetector.PowerState = { PowerDetector.getPowerState() },
) {
    private val siteKey = "${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)}"

    /** Null without a DB: the unchanged routine fetch (no window). */
    fun current(nowMs: Long): HourlyWindowPolicy.Window? {
        val dao = weatherDao ?: return null
        val (isCharging, level) = powerState()
        val last = dao.getRecentLogsByTags(listOf(TAG), limit = 50)
            .filter { it.message.startsWith("source=$weatherSource site=$siteKey") }
            .maxOfOrNull { it.timestamp }
        return HourlyWindowPolicy.choose(isCharging, level, last, nowMs)
    }

    fun markFull() {
        weatherDao?.log(tag = TAG, message = "source=$weatherSource site=$siteKey window=FULL", level = "INFO")
    }

    companion object {
        const val TAG = "HOURLY_FULL_FETCH"
    }
}
