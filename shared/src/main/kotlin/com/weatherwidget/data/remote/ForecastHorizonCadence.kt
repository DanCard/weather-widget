package com.weatherwidget.data.remote

import com.weatherwidget.data.local.LocationMatch
import java.time.Instant
import java.time.ZoneId

/**
 * How often each source's forecast is fetched — ONE rule for every source, daily and hourly together,
 * Android and desktop. Every fetch is [Horizon.LONG], the whole ≈ 8-day reach (user, 2026-10-25:
 * "Prefer all updates be long"; performance/261025-every-forecast-fetch-long.md). The 3-day SHORT
 * fetch of 2026-10-16 (performance/261016-unified-long-short-forecast-horizons.md) is gone; its
 * timing is now the LONG one:
 *
 * | Battery | Screen | Every fetch (LONG) |
 * |---|---|---|
 * | ≥ 70 % or charging | on | every 4 h |
 * | ≥ 70 % or charging | off | every 8 h |
 * | < 70 % | on or off | every 24 h |
 *
 * A fetch downloads the source's whole reach (Google: [LONG_ASK_HOURS]) and stores every daily row and
 * up to 8 days of live hourly ([HourlyHorizon.longHours]). Snapshots keep [HourlyHorizons.ROUTINE_HOURS].
 *
 * No displayed-vs-other split, no view-age bands, no per-provider case. "Due" is measured from one
 * per-source + site marker ([Marker]), never from the rows' `fetchedAt`.
 *
 * **Pre-wake** (2026-10-19; performance/261019-pre-alarm-long-fetch-while-charging.md): on a charger,
 * the fetch also comes due in the [PRE_WAKE_LEAD_MS] before the user's next alarm ([decide]'s
 * `wakeAtMs`), so the first glance of the morning sees a forecast fetched minutes ago. The overnight
 * fetch it would overwrite before anyone looks is skipped: on a charger with the screen off, a site
 * fetched in the last 24 h waits for the pre-wake when it comes within the interval
 * (performance/261019-learned-unplug-time-and-overnight-short-skip.md).
 */
object ForecastHorizonCadence {
    /** The one horizon left (2026-10-25). A null `Horizon?` is a fetch the cadence did not judge (no context, an on-demand day). */
    enum class Horizon { LONG }

    private const val HOUR_MS = 3_600_000L

    /** At or above this level (or on a charger) the battery is "high". */
    const val HIGH_BATTERY_PERCENT = 70

    const val SCREEN_ON_HOURS = 4
    const val SCREEN_OFF_HOURS = 8
    const val LOW_BATTERY_HOURS = 24

    /** 8 days: what a fetch asks a source that bills per day for (capped by its reach). */
    const val LONG_ASK_HOURS = HourlyHorizons.LONG_HOURS

    /**
     * How much older than the newest fetch a day's row may be and still be shown, when a newer fetch
     * covers fewer days (≤ 24 h apart, plus slack for a late check). Older rows give way to the climate
     * normal ([showsCarriedRow]).
     */
    const val MAX_CARRIED_ROW_AGE_HOURS = 36

    /** How long before the alarm a charging phone fetches. */
    const val PRE_WAKE_LEAD_MS = 45 * 60_000L

    /** An alarm further away than this (a weekend with no alarm set) is not this night's wake. */
    const val PRE_WAKE_MAX_AHEAD_HOURS = 14

    /** A fetch this close before the alarm already serves it (a snooze never fetches twice). */
    const val PRE_WAKE_MIN_GAP_HOURS = 3

    /** Where inside the lead a timed check aims: 5 min in, so a check a little late still lands in it. */
    const val PRE_WAKE_CHECK_SLACK_MS = 5 * 60_000L

    /** A check that lands a moment early still counts. */
    const val DUE_GRACE_MS = 120_000L

    fun isHighBattery(isCharging: Boolean, batteryLevel: Int): Boolean =
        isCharging || batteryLevel >= HIGH_BATTERY_PERCENT

    fun intervalHours(isCharging: Boolean, batteryLevel: Int, isScreenOn: Boolean): Int = when {
        !isHighBattery(isCharging, batteryLevel) -> LOW_BATTERY_HOURS
        isScreenOn -> SCREEN_ON_HOURS
        else -> SCREEN_OFF_HOURS
    }

    fun intervalMs(isCharging: Boolean, batteryLevel: Int, isScreenOn: Boolean): Long =
        intervalHours(isCharging, batteryLevel, isScreenOn) * HOUR_MS

    /** One source's decision at a site. [lastFetchMs]: the newest fetch the marker recorded. */
    data class Decision(
        val intervalHours: Int,
        val lastFetchMs: Long?,
        val dueAtMs: Long,
        /** What a scheduled check fetches now: [Horizon.LONG] when due, else null. */
        val due: Horizon?,
        /** When the fetch comes due ahead of its interval for the alarm; null when the pre-wake rule is off. */
        val preWakeAtMs: Long? = null,
        /** The interval's fetch is off tonight: the pre-wake one comes first ([dueAtMs] is [preWakeAtMs]). */
        val waitsForPreWake: Boolean = false,
    )

    fun decide(
        isCharging: Boolean,
        batteryLevel: Int,
        isScreenOn: Boolean,
        lastFetchMs: Long?,
        nowMs: Long,
        wakeAtMs: Long? = null,
    ): Decision {
        val hours = intervalHours(isCharging, batteryLevel, isScreenOn)
        val preWakeAt = preWakeAtMs(isCharging, lastFetchMs, wakeAtMs, nowMs)
        // A fetch a day old still runs: the wait only re-phases the overnight one.
        val waits = preWakeAt != null && lastFetchMs != null && isCharging && !isScreenOn &&
            preWakeAt - nowMs <= hours * HOUR_MS && nowMs - lastFetchMs < LOW_BATTERY_HOURS * HOUR_MS
        val intervalDueAt = dueAtMs(lastFetchMs, hours, nowMs)
        val dueAt = when {
            waits -> preWakeAt!!
            preWakeAt != null -> minOf(intervalDueAt, preWakeAt)
            else -> intervalDueAt
        }
        val due = Horizon.LONG.takeIf { nowMs >= dueAt }
        return Decision(hours, lastFetchMs, dueAt, due, preWakeAt, waits)
    }

    /**
     * When the fetch comes due for the alarm at [wakeAtMs]: [PRE_WAKE_LEAD_MS] before it, on a charger,
     * for an alarm within [PRE_WAKE_MAX_AHEAD_HOURS] that no fetch has served yet (none within
     * [PRE_WAKE_MIN_GAP_HOURS] before it). Null once the alarm has passed — on battery it never applies.
     */
    fun preWakeAtMs(isCharging: Boolean, lastFetchMs: Long?, wakeAtMs: Long?, nowMs: Long): Long? {
        val wake = wakeAtMs ?: return null
        if (!isCharging || nowMs >= wake || wake - nowMs > PRE_WAKE_MAX_AHEAD_HOURS * HOUR_MS) return null
        if (lastFetchMs != null && lastFetchMs >= wake - PRE_WAKE_MIN_GAP_HOURS * HOUR_MS) return null
        return wake - PRE_WAKE_LEAD_MS
    }

    private fun dueAtMs(lastMs: Long?, intervalHours: Int, nowMs: Long): Long =
        lastMs?.let { it + intervalHours * HOUR_MS - DUE_GRACE_MS } ?: nowMs

    /**
     * When a platform should wake to check the pre-wake rule for the alarm at [wakeAtMs] — a timed
     * check, because a periodic tick longer than the lead can miss it. Now when already inside the
     * lead; null when there is no alarm, it has passed, or it is more than [PRE_WAKE_MAX_AHEAD_HOURS]
     * away. Whether that check then fetches is [decide]'s call, not this one's.
     */
    fun preWakeCheckAtMs(wakeAtMs: Long?, nowMs: Long): Long? {
        val wake = wakeAtMs ?: return null
        if (nowMs >= wake || wake - nowMs > PRE_WAKE_MAX_AHEAD_HOURS * HOUR_MS) return null
        return maxOf(nowMs, wake - PRE_WAKE_LEAD_MS + PRE_WAKE_CHECK_SLACK_MS)
    }

    /** Hours a fetch of [sourceId] asks for: only a source billed per day honours more than its routine reach. */
    fun askHours(sourceId: String): Int {
        val reach = HourlyHorizons.of(sourceId)
        return if (reach.costsPerExtraDay) minOf(LONG_ASK_HOURS, reach.maxHours) else reach.routineHours
    }

    /** Hours of live hourly a fetch of [sourceId] stores: its whole download up to 8 days ([HourlyHorizon.longHours]). */
    fun storeHours(sourceId: String): Int = HourlyHorizons.of(sourceId).longHours

    /** Hours of hourly a fetch of [sourceId] snapshots into history: the routine 72 h. */
    fun snapshotHours(sourceId: String): Int = HourlyHorizons.of(sourceId).routineHours

    /** Whether a day's newest row, fetched at [rowBatchMs], is shown beside the newest fetch at [newestBatchMs]. */
    fun showsCarriedRow(rowBatchMs: Long, newestBatchMs: Long): Boolean =
        newestBatchMs - rowBatchMs <= MAX_CARRIED_ROW_AGE_HOURS * HOUR_MS

    /** `FETCH_PLAN` fragment for one source: `NWS:LONG(every=4h/5h)` — interval / age of last fetch. */
    fun logFragment(
        sourceId: String,
        decision: Decision,
        nowMs: Long,
        fullyBlockedUntilMs: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val age = decision.lastFetchMs?.let { "${(nowMs - it) / HOUR_MS}h" } ?: "none"
        return "$sourceId:${decision.due ?: "none"}" +
            "(every=${decision.intervalHours}h/$age" +
            (if (decision.waitsForPreWake) ",waits(preWake)" else "") +
            (fullyBlockedUntilMs?.let { ",quotaHeldMin=${(it - nowMs) / 60_000L}" } ?: "") +
            (decision.preWakeAtMs?.let { ",preWake=" + Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withSecond(0).withNano(0) } ?: "") + ")"
    }

    /**
     * The newest fetch the app recorded, valid for one site only: a new site is due at once. Both
     * platforms keep one marker per source.
     */
    data class Marker(val lat: Double, val lon: Double, val atMs: Long) {
        fun forSite(lat: Double, lon: Double): Long? =
            atMs.takeIf { LocationMatch.sameSite(LocationMatch.quantize(lat), LocationMatch.quantize(lon), this.lat, this.lon) }

        fun encode(): String = "$lat|$lon|$atMs"

        companion object {
            fun at(lat: Double, lon: Double, atMs: Long) =
                Marker(LocationMatch.quantize(lat), LocationMatch.quantize(lon), atMs)

            fun decode(raw: String?): Marker? {
                val parts = raw?.split('|') ?: return null
                if (parts.size != 3) return null
                return Marker(
                    parts[0].toDoubleOrNull() ?: return null,
                    parts[1].toDoubleOrNull() ?: return null,
                    parts[2].toLongOrNull() ?: return null,
                )
            }
        }
    }
}
