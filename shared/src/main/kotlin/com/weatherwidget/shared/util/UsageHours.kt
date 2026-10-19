package com.weatherwidget.shared.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * When the device is used, per hour of the week — to fetch less in hours that are usually unused
 * (performance/261019-android-usage-hours-slow-fetching-when-unused.md). **Phase 1: recorded and
 * logged only; nothing acts on it yet.**
 *
 * 168 slots (7 days × 24 local hours), each the probability that the device is used in that hour of
 * the week, as an exponentially weighted average updated once per completed hour:
 * `p ← p·(1−[ALPHA]) + [ALPHA]·used`, which is about the last 4 weeks. No timestamps are kept — only
 * these aggregates and the current hour (privacy, as for `source_view_days`, user 2026-10-10).
 *
 * The table starts from a **night prior** (user, 2026-10-19): [NIGHT_START_HOUR]–[NIGHT_END_HOUR] local
 * at [NIGHT_PRIOR] (unused), every other hour at [DAY_PRIOR]. A sighting is reliable evidence of use;
 * a missing one is not (Android hears `SCREEN_ON` only while the process is alive), so the prior is
 * what learning has to argue with.
 */
object UsageHours {
    const val SLOTS = 7 * 24
    const val ALPHA = 0.25
    const val NIGHT_START_HOUR = 0
    const val NIGHT_END_HOUR = 5
    const val NIGHT_PRIOR = 0.05
    const val DAY_PRIOR = 0.5

    /** Below this an hour counts as unused. */
    const val UNUSED_BELOW = 0.1

    /** At or above this an hour is listed as used in `USAGE_HOURS`. */
    const val USED_AT_LEAST = 0.5

    private const val HOUR_MS = 3_600_000L

    /**
     * The table, the hour being recorded ([currentHour], epoch hours; −1 = none yet) and whether it
     * was used, plus the counters for the day being checked ([checkDay], local epoch day) that
     * `USAGE_PREDICTION_CHECK` reports.
     */
    data class State(
        val p: DoubleArray,
        val currentHour: Long,
        val currentUsed: Boolean,
        val hoursRolled: Int,
        val checkDay: Long,
        val unusedHours: Int,
        val missHours: List<Int>,
        /** The last completed day's `USAGE_PREDICTION_CHECK` line; empty until a day completes. */
        val completedCheck: String = "",
    ) {
        override fun equals(other: Any?): Boolean =
            other is State && p.contentEquals(other.p) && currentHour == other.currentHour &&
                currentUsed == other.currentUsed && hoursRolled == other.hoursRolled && checkDay == other.checkDay &&
                unusedHours == other.unusedHours && missHours == other.missHours && completedCheck == other.completedCheck

        override fun hashCode(): Int = p.contentHashCode() * 31 + currentHour.hashCode()
    }

    fun initial(): State = State(
        p = DoubleArray(SLOTS) { slot -> if (slot % 24 in NIGHT_START_HOUR until NIGHT_END_HOUR) NIGHT_PRIOR else DAY_PRIOR },
        currentHour = -1,
        currentUsed = false,
        hoursRolled = 0,
        checkDay = -1,
        unusedHours = 0,
        missHours = emptyList(),
    )

    /** Monday 00:00 = slot 0. */
    fun slot(epochHour: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(epochHour * HOUR_MS).atZone(zone)
        return (t.dayOfWeek.value - DayOfWeek.MONDAY.value) * 24 + t.hour
    }

    private fun localDay(epochHour: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochHour * HOUR_MS).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Brings [state] to the hour of [nowMs]: every completed hour since the last one recorded updates its
     * slot — used if sighted, unused otherwise (a device off overnight was not used) — and counts toward
     * the day's check (an hour predicted unused that was used is a miss). Gaps over [SLOTS] hours update
     * each slot once, as unused.
     */
    fun advance(state: State, nowMs: Long, zone: ZoneId): State {
        val nowHour = Math.floorDiv(nowMs, HOUR_MS)
        if (state.currentHour < 0) return state.copy(currentHour = nowHour, currentUsed = false)
        if (nowHour <= state.currentHour) return state
        val p = state.p.copyOf()
        var rolled = state.hoursRolled
        var checkDay = state.checkDay
        var unused = state.unusedHours
        var misses = state.missHours
        var completed = state.completedCheck
        val first = maxOf(state.currentHour, nowHour - SLOTS)
        for (h in first until nowHour) {
            val used = h == state.currentHour && state.currentUsed
            val s = slot(h, zone)
            val day = localDay(h, zone)
            if (day != checkDay) {
                if (checkDay >= 0) completed = checkLine(checkDay, unused, misses)
                checkDay = day
                unused = 0
                misses = emptyList()
            }
            if (p[s] < UNUSED_BELOW) {
                unused++
                if (used) misses = misses + (s % 24)
            }
            p[s] = p[s] * (1 - ALPHA) + (if (used) ALPHA else 0.0)
            rolled++
        }
        // The day's last hour is rolled: it is complete as soon as the next one begins.
        val nowDay = localDay(nowHour, zone)
        if (checkDay >= 0 && nowDay != checkDay) {
            completed = checkLine(checkDay, unused, misses)
            checkDay = nowDay
            unused = 0
            misses = emptyList()
        }
        return State(p, nowHour, false, rolled, checkDay, unused, misses, completed)
    }

    /** The device was used at [nowMs]. */
    fun sight(state: State, nowMs: Long, zone: ZoneId): State =
        advance(state, nowMs, zone).copy(currentUsed = true)

    /** Whether the hour of [nowMs] and the next are both unused (phase 2's test; logged only for now). */
    fun isUnused(state: State, nowMs: Long, zone: ZoneId): Boolean {
        val h = Math.floorDiv(nowMs, HOUR_MS)
        return state.p[slot(h, zone)] < UNUSED_BELOW && state.p[slot(h + 1, zone)] < UNUSED_BELOW
    }

    /** `USAGE_HOURS weekday=07-09,12,17-23 weekend=09-23 weeks=3`: hours with p ≥ [USED_AT_LEAST], averaged per day type. */
    fun hoursLogLine(state: State): String {
        fun used(days: IntRange): String {
            val hours = (0 until 24).filter { h -> days.map { d -> state.p[d * 24 + h] }.average() >= USED_AT_LEAST }
            return ranges(hours).ifEmpty { "none" }
        }
        val weeks = state.hoursRolled / SLOTS
        return "weekday=${used(0..4)} weekend=${used(5..6)} weeks=$weeks"
    }

    /**
     * `USAGE_PREDICTION_CHECK day=2026-10-19 predictedUnusedHours=5 misses=1 missHours=03`: the last
     * completed day's hours called unused, and use in them. Null until a day has completed.
     */
    fun checkLogLine(state: State): String? = state.completedCheck.ifEmpty { null }

    private fun checkLine(day: Long, unused: Int, misses: List<Int>): String =
        "day=${java.time.LocalDate.ofEpochDay(day)} predictedUnusedHours=$unused misses=${misses.size} " +
            "missHours=${ranges(misses.distinct().sorted()).ifEmpty { "none" }}"

    /** `0,1,2,5,7,8` → `00-02,05,07-08`. */
    fun ranges(hours: List<Int>): String {
        if (hours.isEmpty()) return ""
        val out = mutableListOf<String>()
        var start = hours[0]
        var prev = hours[0]
        fun flush() { out += if (start == prev) "%02d".format(start) else "%02d-%02d".format(start, prev) }
        for (h in hours.drop(1)) {
            if (h == prev + 1) { prev = h; continue }
            flush(); start = h; prev = h
        }
        flush()
        return out.joinToString(",")
    }

    fun encode(state: State): String =
        listOf(
            state.currentHour, if (state.currentUsed) 1 else 0, state.hoursRolled, state.checkDay, state.unusedHours,
            state.missHours.joinToString(":"), state.p.joinToString(":") { it.toString() },
            state.completedCheck,
        ).joinToString(";")

    fun decode(raw: String?): State? {
        val parts = raw?.split(';') ?: return null
        if (parts.size != 8) return null
        val p = parts[6].split(':').mapNotNull { it.toDoubleOrNull() }
        if (p.size != SLOTS) return null
        return State(
            p = p.toDoubleArray(),
            currentHour = parts[0].toLongOrNull() ?: return null,
            currentUsed = parts[1] == "1",
            hoursRolled = parts[2].toIntOrNull() ?: return null,
            checkDay = parts[3].toLongOrNull() ?: return null,
            unusedHours = parts[4].toIntOrNull() ?: return null,
            missHours = parts[5].split(':').mapNotNull { it.toIntOrNull() },
            completedCheck = parts[7],
        )
    }
}
