package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.ForecastHorizonCadence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.pow

/**
 * When the device will next leave its charger, learned from [ChargeHistory] — the pre-unplug fetch's
 * target ([ForecastHorizonCadence.preWakeAtMs]; plans/261028-learned-unplug-prediction.md). A fetch
 * before an unplug is the only one a charger pays for that the battery would otherwise; one while the
 * device stays plugged is wasted, since screen-on can fetch then for free.
 *
 * Three kinds of evidence, each a cluster of unplug times of day ([CLUSTER_GAP_MINUTES] apart at most),
 * judged over past days by hit rate:
 * - **Weekly** — the same weekday over [WEEKLY_LOOKBACK_DAYS] (basketball every Tuesday at 11:30);
 *   active from [WEEKLY_MIN_HITS] hits.
 * - **Daily** — every weekday (or weekend day) over [DAILY_LOOKBACK_DAYS]; active from [DAILY_MIN_HITS].
 * - **Alarm** — a clock alarm, trusted only as far as unplugs have followed it within
 *   [ALARM_FOLLOW_MINUTES]: a Beta(1, 1) prior updated per past alarm near that time of day, used while
 *   its mean is above [MIN_HIT_RATE]. A bedtime alarm the phone sleeps through on the charger never earns it.
 *
 * A day is a *hit* when an unplug fell in the window, a *miss* when the device was charging at the
 * window's start and stayed so through it, and neither when it was not known to be charging. Past days
 * weigh half as much every [HALF_LIFE_DAYS], so a new habit counts after two occurrences and a dropped
 * one fades in about a month. A prediction is the cluster's earliest unplug — a fetch a little early
 * is still fresh; one after the unplug is lost. Times of day are local, so DST does not shift them.
 */
object UnplugPredictor {
    const val WEEKLY_LOOKBACK_DAYS = ChargeHistory.RETENTION_DAYS
    const val DAILY_LOOKBACK_DAYS = 21
    const val WEEKLY_MIN_HITS = 2
    const val DAILY_MIN_HITS = 4
    const val MIN_HIT_RATE = 0.5
    const val HALF_LIFE_DAYS = 21.0
    const val CLUSTER_GAP_MINUTES = 30
    const val WINDOW_PAD_MINUTES = 30
    const val ALARM_BUCKET_MINUTES = 30
    const val ALARM_FOLLOW_MINUTES = 90

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    enum class Kind { WEEKLY, DAILY, ALARM }

    enum class DayGroup { WEEKDAY, WEEKEND }

    fun dayGroup(date: LocalDate): DayGroup =
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) DayGroup.WEEKEND else DayGroup.WEEKDAY

    /**
     * One cluster of unplug times. [label]: the weekday (`tue`) or group (`weekday`). Minutes of day:
     * [earliestMinute]–[latestMinute] are the observed unplugs; the judged window pads them.
     */
    data class Pattern(
        val kind: Kind,
        val label: String,
        val earliestMinute: Int,
        val latestMinute: Int,
        val hits: Int,
        val misses: Int,
        val rate: Double,
    ) {
        val active: Boolean
            get() = rate >= MIN_HIT_RATE && hits >= if (kind == Kind.WEEKLY) WEEKLY_MIN_HITS else DAILY_MIN_HITS

        fun appliesTo(date: LocalDate): Boolean = when (kind) {
            Kind.WEEKLY -> label == dayLabel(date.dayOfWeek)
            Kind.DAILY -> label == dayGroup(date).name.lowercase()
            Kind.ALARM -> false
        }
    }

    /** How far unplugs have followed alarms near one alarm's time of day; [offsetMinutes] is the earliest follow. */
    data class AlarmTrust(val hits: Int, val misses: Int, val rate: Double, val offsetMinutes: Int) {
        val trusted: Boolean get() = rate > MIN_HIT_RATE
    }

    data class Prediction(val atMs: Long, val kind: Kind, val label: String, val rate: Double) {
        /** `weekly(tue)`, `daily(weekday)`, `alarm`. */
        val source: String get() = if (kind == Kind.ALARM) "alarm" else "${kind.name.lowercase()}($label)"
    }

    fun dayLabel(day: DayOfWeek): String = day.name.take(3).lowercase()

    /** Every cluster with enough unplugs to be judged, weekly and daily, active or not. */
    fun patterns(state: ChargeHistory.State, nowMs: Long, zone: ZoneId): List<Pattern> {
        val unplugs = state.sessions.map { it.unpluggedAtMs }.filter { it <= nowMs }.map { at(it, zone) }
        val weekly = DayOfWeek.entries.flatMap { day ->
            val minutes = unplugs.filter { it.dayOfWeek == day && ageDays(it, nowMs) < WEEKLY_LOOKBACK_DAYS }.map { minuteOf(it) }
            clusters(minutes, WEEKLY_MIN_HITS).map { (lo, hi) ->
                judge(state, Kind.WEEKLY, dayLabel(day), lo, hi, nowMs, zone, WEEKLY_LOOKBACK_DAYS) { it.dayOfWeek == day }
            }
        }
        val daily = DayGroup.entries.flatMap { group ->
            val minutes = unplugs.filter { dayGroup(it.toLocalDate()) == group && ageDays(it, nowMs) < DAILY_LOOKBACK_DAYS }.map { minuteOf(it) }
            clusters(minutes, DAILY_MIN_HITS).map { (lo, hi) ->
                judge(state, Kind.DAILY, group.name.lowercase(), lo, hi, nowMs, zone, DAILY_LOOKBACK_DAYS) { dayGroup(it) == group }
            }
        }
        return weekly + daily
    }

    /**
     * What past alarms near [alarmMs]'s time of day say about unplugging after it. Any day counts: an
     * alarm is identified by its time, and splitting weekday from weekend would halve a daily alarm's evidence.
     */
    fun alarmTrust(state: ChargeHistory.State, alarmMs: Long, nowMs: Long, zone: ZoneId): AlarmTrust {
        val alarm = at(alarmMs, zone)
        var hitW = 0.0
        var missW = 0.0
        var hits = 0
        var misses = 0
        var offset: Int? = null
        for (pastMs in state.alarms) {
            if (pastMs + ALARM_FOLLOW_MINUTES * MINUTE_MS > nowMs) continue
            val past = at(pastMs, zone)
            if (abs(minuteOf(past) - minuteOf(alarm)) > ALARM_BUCKET_MINUTES) continue
            val w = weight(pastMs, nowMs)
            val follow = state.unplugsBetween(pastMs, pastMs + ALARM_FOLLOW_MINUTES * MINUTE_MS).minOrNull()
            when {
                follow != null -> {
                    hits++
                    hitW += w
                    val minutes = ((follow - pastMs) / MINUTE_MS).toInt()
                    offset = minOf(offset ?: minutes, minutes)
                }
                state.chargingAt(pastMs) -> {
                    misses++
                    missW += w
                }
            }
        }
        return AlarmTrust(hits, misses, (1 + hitW) / (2 + hitW + missW), offset ?: 0)
    }

    /**
     * The next predicted unplug after [nowMs] within [ForecastHorizonCadence.PRE_WAKE_MAX_AHEAD_HOURS]:
     * the earliest of today's and tomorrow's active patterns and a trusted [nextAlarmMs]. A window an
     * unplug already answered (one since [ALARM_FOLLOW_MINUTES] before it) is not predicted again.
     */
    fun predictNext(state: ChargeHistory.State, nextAlarmMs: Long?, nowMs: Long, zone: ZoneId): Prediction? {
        val today = at(nowMs, zone).toLocalDate()
        val active = patterns(state, nowMs, zone).filter { it.active }
        val fromPatterns = listOf(today, today.plusDays(1)).flatMap { date ->
            active.filter { it.appliesTo(date) }.map { p ->
                Prediction(date.atStartOfDay(zone).plusMinutes(p.earliestMinute.toLong()).toInstant().toEpochMilli(), p.kind, p.label, p.rate)
            }
        }
        val fromAlarm = nextAlarmMs?.let { alarm ->
            alarmTrust(state, alarm, nowMs, zone).takeIf { it.trusted }?.let {
                Prediction(alarm + it.offsetMinutes * MINUTE_MS, Kind.ALARM, "alarm", it.rate)
            }
        }
        return (fromPatterns + listOfNotNull(fromAlarm))
            .filter { it.atMs > nowMs && it.atMs - nowMs <= ForecastHorizonCadence.PRE_WAKE_MAX_AHEAD_HOURS * HOUR_MS }
            .filter { state.unplugsBetween(it.atMs - ALARM_FOLLOW_MINUTES * MINUTE_MS, nowMs).isEmpty() }
            .minByOrNull { it.atMs }
    }

    /**
     * `UNPLUG_PATTERNS` message: `tue=11:40-11:50 p=1.00(2/0) on; alarm=07:00 p=0.25(0/2) off;
     * sessions=9 estimated=1/9` — `estimated` counts unplugs placed at a sighting, not heard.
     */
    fun logLine(state: ChargeHistory.State, nextAlarmMs: Long?, nowMs: Long, zone: ZoneId): String {
        val patterns = patterns(state, nowMs, zone)
        val parts = patterns.map { p ->
            "${p.label}=${hhmm(p.earliestMinute)}-${hhmm(p.latestMinute)} p=${"%.2f".format(p.rate)}(${p.hits}/${p.misses}) ${if (p.active) "on" else "off"}"
        }.ifEmpty { listOf("patterns=none") }
        val alarm = nextAlarmMs?.let { a ->
            val t = alarmTrust(state, a, nowMs, zone)
            "alarm=${hhmm(minuteOf(at(a, zone)))} p=${"%.2f".format(t.rate)}(${t.hits}/${t.misses}) ${if (t.trusted) "on" else "off"}"
        } ?: "alarm=none"
        return (parts + alarm + "sessions=${state.sessions.size} estimated=${state.sessions.count { !it.exact }}/${state.sessions.size}").joinToString("; ")
    }

    /**
     * Hit rate of the window around [lo]–[hi] over the past days [matches] keeps; the window must have
     * closed by [nowMs] to be judged.
     */
    private fun judge(
        state: ChargeHistory.State,
        kind: Kind,
        label: String,
        lo: Int,
        hi: Int,
        nowMs: Long,
        zone: ZoneId,
        lookbackDays: Int,
        matches: (LocalDate) -> Boolean,
    ): Pattern {
        val today = at(nowMs, zone).toLocalDate()
        var hitW = 0.0
        var missW = 0.0
        var hits = 0
        var misses = 0
        for (back in 0 until lookbackDays) {
            val date = today.minusDays(back.toLong())
            if (!matches(date)) continue
            val start = date.atStartOfDay(zone).plusMinutes((lo - WINDOW_PAD_MINUTES).toLong()).toInstant().toEpochMilli()
            val end = date.atStartOfDay(zone).plusMinutes((hi + WINDOW_PAD_MINUTES).toLong()).toInstant().toEpochMilli()
            if (end > nowMs) continue
            val w = weight(start, nowMs)
            when {
                state.unplugsBetween(start, end).isNotEmpty() -> { hits++; hitW += w }
                state.chargingAt(start) -> { misses++; missW += w }
            }
        }
        val rate = if (hitW + missW > 0) hitW / (hitW + missW) else 0.0
        return Pattern(kind, label, lo, hi, hits, misses, rate)
    }

    /** Groups of sorted minutes no more than [CLUSTER_GAP_MINUTES] apart, with at least [minSize] members. */
    private fun clusters(minutes: List<Int>, minSize: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var group = mutableListOf<Int>()
        for (m in minutes.sorted()) {
            if (group.isNotEmpty() && m - group.last() > CLUSTER_GAP_MINUTES) {
                if (group.size >= minSize) out += group.first() to group.last()
                group = mutableListOf()
            }
            group += m
        }
        if (group.size >= minSize) out += group.first() to group.last()
        return out
    }

    private fun weight(atMs: Long, nowMs: Long): Double = 0.5.pow((nowMs - atMs).toDouble() / DAY_MS / HALF_LIFE_DAYS)

    private fun at(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    private fun minuteOf(t: ZonedDateTime): Int = t.hour * 60 + t.minute

    private fun ageDays(t: ZonedDateTime, nowMs: Long): Long = (nowMs - t.toInstant().toEpochMilli()) / DAY_MS

    private fun hhmm(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}
