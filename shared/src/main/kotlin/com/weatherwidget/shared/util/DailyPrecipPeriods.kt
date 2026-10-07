package com.weatherwidget.shared.util

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day/night precip chance stored on a daily forecast row — one rule for Android and desktop.
 *
 * Until 2026-10-07 the platforms stored different things in the same columns: Android computed the
 * 8am–8pm / 8pm–8am hourly max from the fetch payload and ignored the provider's own values; desktop
 * stored the provider's values (empty for sources without them). The stored values are what
 * `daily_history` freezes and what fallbacks read, so the two drifted. A Google one-page
 * `forecast/hours` fetch also made the payload stop at 24 h, which would have blanked days 2–3 on
 * Android. See plans/261007-shared-daily-precip-periods.md.
 *
 * Rule (user, 2026-10-07): the **provider's own value** when it supplies one — NWS 12-hour period
 * chances are stored as-is by an earlier decision (commit 3fa341b6: "Tomorrow 30%" is not overridden
 * by one 95% hour), and Google's daytime/nighttime cover all 10 days, so a one-page fetch cannot blank
 * days 2–3. Otherwise the 8am–8pm / 8pm–8am max over the source's hourly rows **as stored after this
 * fetch's hourly save** (the payload plus any hours a one-page fetch left in place). The display keeps
 * its own hourly-first order ([DailyRainLabels.resolveLiveDayNightChance]); this is what is stored.
 */
object DailyPrecipPeriods {
    data class Periods(val day: Int?, val night: Int?)

    /** Earliest instant whose hourly rows can matter for [firstDate]'s periods. */
    fun readStartMs(firstDate: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        firstDate.atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()

    /** Instant after which hourly rows cannot matter for [lastDate]'s periods. */
    fun readEndMs(lastDate: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        lastDate.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()

    /**
     * Rows stored under exactly this site's write key — the key both platforms quantize hourly rows
     * to ([LocationMatch.quantize]). A proximity-box read can include a neighbouring fragment.
     */
    fun atSite(rows: List<HourlyForecast>, latitude: Double, longitude: Double): List<HourlyForecast> {
        val lat = LocationMatch.quantize(latitude)
        val lon = LocationMatch.quantize(longitude)
        return rows.filter { it.locationLat == null || (it.locationLat == lat && it.locationLon == lon) }
    }

    fun resolve(
        targetDate: LocalDate,
        storedHourly: List<HourlyForecast>,
        providerDay: Int?,
        providerNight: Int?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Periods {
        val computed = DailyRainLabels.periodMaxima(storedHourly, targetDate, zoneId)
        return Periods(
            day = providerDay ?: computed.dayMax,
            night = providerNight ?: computed.nightMax,
        )
    }
}
