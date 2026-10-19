package com.weatherwidget.data.remote

import com.weatherwidget.data.local.LocationMatch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How often each source's forecast is fetched, and how far ahead — ONE rule for every source, daily
 * and hourly together, Android and desktop (user, 2026-10-16;
 * performance/261016-unified-long-short-forecast-horizons.md).
 *
 * | Battery | Screen | [Horizon.LONG] (≈ 8 days) | [Horizon.SHORT] (3 days) |
 * |---|---|---|---|
 * | ≥ 70 % or charging | on | every 8 h | every 4 h |
 * | ≥ 70 % or charging | off | every 24 h | every 8 h |
 * | < 70 % | on or off | every 24 h | none |
 *
 * A LONG fetch downloads the source's whole reach (Google: [LONG_ASK_HOURS]) and stores every daily
 * row and up to 8 days of live hourly ([HourlyHorizon.longHours]); a SHORT one asks [SHORT_HOURS] and
 * stores only the next [SHORT_DAYS] days of daily rows and [HourlyHorizons.ROUTINE_HOURS] of hourly.
 * Snapshots keep [HourlyHorizons.ROUTINE_HOURS] either way. A LONG fetch also counts as a SHORT one.
 *
 * No displayed-vs-other split, no view-age bands, no per-provider case. "Due" is measured from two
 * per-source + site markers ([Marker]), never from the rows' `fetchedAt`.
 *
 * **Pre-wake** (2026-10-19; performance/261019-pre-alarm-long-fetch-while-charging.md): on a charger,
 * LONG also comes due in the [PRE_WAKE_LEAD_MS] before the user's next alarm ([decide]'s `wakeAtMs`),
 * so the first glance of the morning sees a forecast fetched minutes ago. It re-phases the overnight
 * LONG fetch rather than adding one: the markers it resets keep the 24 h one from coming due at night.
 * The SHORT fetch it would overwrite before anyone looks is skipped: on a charger with the screen off,
 * SHORT is not due while the pre-wake comes within [SHORT_SCREEN_OFF_HOURS]
 * (performance/261019-learned-unplug-time-and-overnight-short-skip.md).
 */
object ForecastHorizonCadence {
    enum class Horizon { SHORT, LONG }

    private const val HOUR_MS = 3_600_000L

    /** At or above this level (or on a charger) the battery is "high". */
    const val HIGH_BATTERY_PERCENT = 70

    /** 8 h, not 12 (user, 2026-10-16: changed after the first version shipped). */
    const val LONG_SCREEN_ON_HOURS = 8
    const val LONG_SCREEN_OFF_HOURS = 24
    const val LONG_LOW_BATTERY_HOURS = 24
    const val SHORT_SCREEN_ON_HOURS = 4
    const val SHORT_SCREEN_OFF_HOURS = 8

    /** Hours a SHORT fetch asks for (and stores). */
    const val SHORT_HOURS = 72

    /** Daily rows a SHORT fetch stores: today and the next two days. */
    const val SHORT_DAYS = 3

    /** 8 days: what a LONG fetch asks a source that bills per day for (capped by its reach). */
    const val LONG_ASK_HOURS = HourlyHorizons.LONG_HOURS

    /**
     * How much older than the newest fetch a day's row may be and still be shown: a SHORT fetch leaves
     * days 4+ to the last LONG one (≤ 24 h apart, plus slack for a late check). Older rows give way to
     * the climate normal, as when a newer fetch covers fewer days ([showsCarriedRow]).
     */
    const val MAX_CARRIED_ROW_AGE_HOURS = 36

    /** How long before the alarm a charging phone fetches LONG. */
    const val PRE_WAKE_LEAD_MS = 45 * 60_000L

    /** An alarm further away than this (a weekend with no alarm set) is not this night's wake. */
    const val PRE_WAKE_MAX_AHEAD_HOURS = 14

    /** A LONG fetch this close before the alarm already serves it (a snooze never fetches twice). */
    const val PRE_WAKE_MIN_GAP_HOURS = 3

    /** Where inside the lead a timed check aims: 5 min in, so a check a little late still lands in it. */
    const val PRE_WAKE_CHECK_SLACK_MS = 5 * 60_000L

    /** A check that lands a moment early still counts. */
    const val DUE_GRACE_MS = 120_000L

    fun isHighBattery(isCharging: Boolean, batteryLevel: Int): Boolean =
        isCharging || batteryLevel >= HIGH_BATTERY_PERCENT

    fun longIntervalHours(isCharging: Boolean, batteryLevel: Int, isScreenOn: Boolean): Int = when {
        !isHighBattery(isCharging, batteryLevel) -> LONG_LOW_BATTERY_HOURS
        isScreenOn -> LONG_SCREEN_ON_HOURS
        else -> LONG_SCREEN_OFF_HOURS
    }

    /** Null: no SHORT fetches (low battery). */
    fun shortIntervalHours(isCharging: Boolean, batteryLevel: Int, isScreenOn: Boolean): Int? = when {
        !isHighBattery(isCharging, batteryLevel) -> null
        isScreenOn -> SHORT_SCREEN_ON_HOURS
        else -> SHORT_SCREEN_OFF_HOURS
    }

    /** The soonest any horizon can come due again: the SHORT interval, else the LONG one. */
    fun soonestIntervalMs(isCharging: Boolean, batteryLevel: Int, isScreenOn: Boolean): Long =
        (shortIntervalHours(isCharging, batteryLevel, isScreenOn)
            ?: longIntervalHours(isCharging, batteryLevel, isScreenOn)) * HOUR_MS

    /**
     * One source's decision at a site. [lastShortMs] is the newest fetch of either horizon (every
     * fetch marks SHORT); [lastLongMs] the newest LONG one.
     */
    data class Decision(
        val longIntervalHours: Int,
        val shortIntervalHours: Int?,
        val lastLongMs: Long?,
        val lastShortMs: Long?,
        val longDueAtMs: Long,
        val shortDueAtMs: Long?,
        /** What a scheduled check fetches now: LONG when due, else SHORT when due, else null. */
        val due: Horizon?,
        /** When LONG comes due ahead of its interval for the alarm; null when the pre-wake rule is off. */
        val preWakeAtMs: Long? = null,
        /** SHORT is off tonight: the pre-wake LONG comes first ([shortDueAtMs] is then null). */
        val shortSkippedForPreWake: Boolean = false,
    ) {
        /** The earlier of the two due instants. */
        val nextDueAtMs: Long get() = shortDueAtMs?.let { minOf(it, longDueAtMs) } ?: longDueAtMs
    }

    fun decide(
        isCharging: Boolean,
        batteryLevel: Int,
        isScreenOn: Boolean,
        lastLongMs: Long?,
        lastShortMs: Long?,
        nowMs: Long,
        wakeAtMs: Long? = null,
    ): Decision {
        val longH = longIntervalHours(isCharging, batteryLevel, isScreenOn)
        val shortH = shortIntervalHours(isCharging, batteryLevel, isScreenOn)
        // A LONG fetch is also a SHORT one.
        val lastShort = listOfNotNull(lastShortMs, lastLongMs).maxOrNull()
        val preWakeAt = preWakeAtMs(isCharging, lastLongMs, wakeAtMs, nowMs)
        val longDueAt = dueAtMs(lastLongMs, longH, nowMs).let { if (preWakeAt != null) minOf(it, preWakeAt) else it }
        val skipShort = shortH != null && isCharging && !isScreenOn && preWakeAt != null &&
            preWakeAt - nowMs <= SHORT_SCREEN_OFF_HOURS * HOUR_MS
        val shortDueAt = shortH?.takeUnless { skipShort }?.let { dueAtMs(lastShort, it, nowMs) }
        val due = when {
            nowMs >= longDueAt -> Horizon.LONG
            shortDueAt != null && nowMs >= shortDueAt -> Horizon.SHORT
            else -> null
        }
        return Decision(longH, shortH, lastLongMs, lastShort, longDueAt, shortDueAt, due, preWakeAt, skipShort)
    }

    /**
     * When LONG comes due for the alarm at [wakeAtMs]: [PRE_WAKE_LEAD_MS] before it, on a charger,
     * for an alarm within [PRE_WAKE_MAX_AHEAD_HOURS] that no LONG fetch has served yet (none within
     * [PRE_WAKE_MIN_GAP_HOURS] before it). Null once the alarm has passed — on battery it never applies.
     */
    fun preWakeAtMs(isCharging: Boolean, lastLongMs: Long?, wakeAtMs: Long?, nowMs: Long): Long? {
        val wake = wakeAtMs ?: return null
        if (!isCharging || nowMs >= wake || wake - nowMs > PRE_WAKE_MAX_AHEAD_HOURS * HOUR_MS) return null
        if (lastLongMs != null && lastLongMs >= wake - PRE_WAKE_MIN_GAP_HOURS * HOUR_MS) return null
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

    /**
     * The horizon of a fetch the user or an event asked for (Refresh, a source switch, a location
     * change, screen-on catch-up): the user's Refresh is always LONG; anything else takes what the
     * cadence says is due, and at least SHORT — it was asked for.
     */
    fun forced(decision: Decision?, userRefresh: Boolean): Horizon = when {
        userRefresh -> Horizon.LONG
        else -> decision?.due ?: Horizon.SHORT
    }

    /** Hours a fetch of [sourceId] asks for: only a source billed per day honours more than its routine reach. */
    fun askHours(sourceId: String, horizon: Horizon): Int {
        val reach = HourlyHorizons.of(sourceId)
        return when (horizon) {
            Horizon.SHORT -> minOf(SHORT_HOURS, reach.routineHours)
            Horizon.LONG -> if (reach.costsPerExtraDay) minOf(LONG_ASK_HOURS, reach.maxHours) else reach.routineHours
        }
    }

    /**
     * Hours of live hourly a fetch of [sourceId] stores: a LONG fetch its whole download up to 8 days
     * ([HourlyHorizon.longHours]), a SHORT one the routine 72 h — days 4+ stay the last LONG fetch's.
     * Snapshots keep the routine 72 h either way ([snapshotHours]).
     */
    fun storeHours(sourceId: String, horizon: Horizon): Int = when (horizon) {
        Horizon.SHORT -> minOf(SHORT_HOURS, HourlyHorizons.of(sourceId).routineHours)
        Horizon.LONG -> HourlyHorizons.of(sourceId).longHours
    }

    /** Hours of hourly a fetch of [sourceId] snapshots into history: the routine 72 h either way. */
    fun snapshotHours(sourceId: String, horizon: Horizon): Int = when (horizon) {
        Horizon.SHORT -> minOf(SHORT_HOURS, HourlyHorizons.of(sourceId).routineHours)
        Horizon.LONG -> HourlyHorizons.of(sourceId).routineHours
    }

    /**
     * Whether a fetch of [horizon] stores the daily row for [date]: LONG stores all; SHORT stores
     * every date up to [today] + [SHORT_DAYS] − 1 (past days included — their settled values still
     * come from the newest fetch).
     */
    fun storesDailyDate(horizon: Horizon?, date: LocalDate, today: LocalDate): Boolean =
        horizon != Horizon.SHORT || !date.isAfter(today.plusDays(SHORT_DAYS - 1L))

    /** Whether a day's newest row, fetched at [rowBatchMs], is shown beside the newest fetch at [newestBatchMs]. */
    fun showsCarriedRow(rowBatchMs: Long, newestBatchMs: Long): Boolean =
        newestBatchMs - rowBatchMs <= MAX_CARRIED_ROW_AGE_HOURS * HOUR_MS

    /** `FETCH_PLAN` fragment for one source: `NWS:SHORT(long=8h/3h,short=4h/0h)` — interval / age of last fetch. */
    fun logFragment(
        sourceId: String,
        decision: Decision,
        nowMs: Long,
        fullyBlockedUntilMs: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        fun age(ms: Long?) = ms?.let { "${(nowMs - it) / HOUR_MS}h" } ?: "none"
        return "$sourceId:${decision.due ?: "none"}" +
            "(long=${decision.longIntervalHours}h/${age(decision.lastLongMs)}," +
            "short=${if (decision.shortSkippedForPreWake) "skip(preWake)" else decision.shortIntervalHours?.let { "${it}h" } ?: "off"}" +
            "/${age(decision.lastShortMs)}" +
            (fullyBlockedUntilMs?.let { ",quotaHeldMin=${(it - nowMs) / 60_000L}" } ?: "") +
            (decision.preWakeAtMs?.let { ",preWake=" + Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withSecond(0).withNano(0) } ?: "") + ")"
    }

    /**
     * The newest fetch of one horizon the app recorded, valid for one site only: a new site is
     * LONG-due at once. Both platforms keep one marker per source per horizon.
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
