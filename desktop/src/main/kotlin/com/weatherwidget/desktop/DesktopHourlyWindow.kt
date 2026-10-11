package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.HourlyCadence
import com.weatherwidget.data.remote.HourlyWindowPolicy
import com.weatherwidget.shared.sourceview.SourceFetchGate
import com.weatherwidget.shared.sourceview.SourceLastView
import com.weatherwidget.shared.sourceview.SourceViewTally
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The desktop's inputs to the shared hourly rules for [weatherSource] at the site: [HourlyWindowPolicy]
 * (NEAR or FULL) and [HourlyCadence] (is hourly due), with the machine's power state and two markers
 * read back from `app_logs` — the decisions themselves are `:shared`, as on Android.
 *
 * Markers are log rows (message `source=… site=lat,lon`): `HOURLY_FULL_FETCH` for the last FULL fetch,
 * `HOURLY_FETCH` for the last fetch that brought hours home (Android keeps both in prefs). app_logs keeps
 * 72 h, longer than the 20 h between FULL fetches and the 24 h longest hourly cadence, so no new table
 * is needed. The service (what to ask a billed source for) and the repository (what to store) both
 * read them before the fetch writes them, so they agree.
 */
internal class DesktopHourlyWindow(
    private val weatherDao: DesktopWeatherDao?,
    private val weatherSource: String,
    latitude: Double,
    longitude: Double,
    private val powerState: () -> PowerDetector.PowerState = { PowerDetector.getPowerState() },
) {
    private val siteKey = "${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)}"
    private val prefix = "source=$weatherSource site=$siteKey"

    /** Null without a DB: the unchanged routine fetch (no window). */
    fun current(nowMs: Long): HourlyWindowPolicy.Window? {
        val dao = weatherDao ?: return null
        val (isCharging, level) = powerState()
        val last = dao.getLatestLogByTagAndMessagePrefix(TAG, prefix)?.timestamp
        return HourlyWindowPolicy.choose(isCharging, level, last, nowMs)
    }

    fun markFull() {
        weatherDao?.log(tag = TAG, message = "$prefix window=FULL", level = "INFO")
    }

    /** The last fetch that brought this source's hours home at the site. */
    fun lastHourlyFetch(): Long? = weatherDao?.getLatestLogByTagAndMessagePrefix(HOURLY_TAG, prefix)?.timestamp

    fun markHourly() {
        weatherDao?.log(tag = HOURLY_TAG, message = prefix, level = "INFO")
    }

    /**
     * [HourlyCadence] for this source: null without a DB (not judged — the daily fetch carries the
     * hours, as before). [isDisplayed]: the popup's source.
     */
    fun hourlyDecision(isDisplayed: Boolean, nowMs: Long): HourlyCadence.Decision? {
        val dao = weatherDao ?: return null
        val (isCharging, level) = powerState()
        val recency = recency(dao, listOf(weatherSource), nowMs)
        return HourlyCadence.decide(
            isCharging = isCharging,
            batteryLevel = level,
            isDisplayed = isDisplayed,
            lastViewedAtMs = recency.of(weatherSource),
            inGrace = recency.inGrace,
            lastHourlyFetchMs = lastHourlyFetch(),
            nowMs = nowMs,
        )
    }

    companion object {
        const val TAG = "HOURLY_FULL_FETCH"
        const val HOURLY_TAG = "HOURLY_FETCH"

        /** Each source's last view (`source_last_view`, else its newest day row) and the tracking grace. */
        fun recency(dao: DesktopWeatherDao, sourceIds: Collection<String>, nowMs: Long): SourceLastView.Recency =
            runCatching {
                val zone = ZoneId.systemDefault()
                val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
                SourceLastView.recency(
                    sourceIds = sourceIds,
                    timestamps = dao.lastViews(),
                    rows = dao.sourceViewDaysSince(SourceViewTally.dayMs(today.minusDays(SourceFetchGate.RECENT_VIEW_DAYS))),
                    trackingSince = dao.sourceViewTrackingStartMs()?.let { LocalDate.ofEpochDay(it / SourceViewTally.DAY_MS) },
                    today = today,
                    zone = zone,
                )
            }.getOrDefault(SourceLastView.Recency.UNKNOWN)
    }
}
