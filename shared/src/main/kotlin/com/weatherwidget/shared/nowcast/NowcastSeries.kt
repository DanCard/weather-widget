package com.weatherwidget.shared.nowcast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One Open-Meteo 15-minute slot. Open-Meteo labels a `minutely_15` value with the **end** of the
 * quarter hour it sums ("preceding 15 minutes"), so the value at 14:15 is rain from 14:00 to 14:15;
 * [startMs] is that 14:00. See `plans/261021-open-meteo-15-min-nowcast.md`.
 */
@Serializable
data class NowcastSlot(
    val startMs: Long,
    val mm: Float,
    val chance: Int? = null,
) {
    val endMs: Long get() = startMs + SLOT_MS
}

/** The next ~3 h of Open-Meteo 15-minute precipitation at one site. */
@Serializable
data class NowcastSeries(
    val slots: List<NowcastSlot>,
    val fetchedAtMs: Long,
) {
    fun hasRainAfter(nowMs: Long): Boolean =
        slots.any { it.endMs > nowMs && it.mm >= NowcastSummary.RAIN_SLOT_MM }
}

const val SLOT_MS = 15L * 60 * 1000

object NowcastParser {
    /** How far ahead a series keeps: the summary reads 2 h, the rest is hysteresis context. */
    const val KEEP_AHEAD_MS = 3L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The `minutely_15` block of any Open-Meteo `/v1/forecast` reply — the full forecast fetch
     * (local ISO times, `timezone=auto`) or the dedicated nowcast request (`timeformat=unixtime`).
     * Keeps the slot containing [nowMs] and those after it, up to [KEEP_AHEAD_MS]. A slot without
     * a precipitation value is dropped (never read as dry). Null when nothing is left.
     */
    fun parseSeries(response: String, fetchedAtMs: Long, nowMs: Long): NowcastSeries? {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return null
        val minutely = root["minutely_15"]?.jsonObject ?: return null
        val times = minutely["time"]?.jsonArray ?: return null
        val mm = minutely["precipitation"]?.jsonArray ?: return null
        val chance = minutely["precipitation_probability"]?.jsonArray
        val zone = root["timezone"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.of("UTC")
        val slots = times.mapIndexedNotNull { i, t ->
            val endMs = labelMs(t.jsonPrimitive, zone) ?: return@mapIndexedNotNull null
            val startMs = endMs - SLOT_MS
            if (endMs <= nowMs || startMs >= nowMs + KEEP_AHEAD_MS) return@mapIndexedNotNull null
            val amount = mm.getOrNull(i)?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it >= 0f }
                ?: return@mapIndexedNotNull null
            NowcastSlot(startMs, amount, chance?.getOrNull(i)?.jsonPrimitive?.intOrNull?.coerceIn(0, 100))
        }
        return slots.takeIf { it.isNotEmpty() }?.let { NowcastSeries(it.sortedBy(NowcastSlot::startMs), fetchedAtMs) }
    }

    private fun labelMs(value: JsonPrimitive, zone: ZoneId): Long? =
        value.longOrNull?.let { it * 1000 }
            ?: value.contentOrNull?.let { text ->
                runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            }
}
