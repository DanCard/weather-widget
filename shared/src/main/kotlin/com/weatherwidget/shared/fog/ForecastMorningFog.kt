package com.weatherwidget.shared.fog

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.DailyHourlySummaries
import java.time.LocalDate
import java.time.ZoneId

/**
 * A day's forecast morning fog from **one source's** hourly rows at one site
 * (plans/261111-daily-bar-morning-fog.md). Read from hours, never from the daily condition words: NWS's
 * "Partly Cloudy then Patchy Fog" is that evening's fog, which belongs to the next morning.
 *
 * An hour is foggy when its words say fog or mist ([FogLevel.fromCondition]) or its visibility is at
 * most [FogLevel.FOG_VISIBILITY_MI]. Its level comes from the provider's visibility when there is one,
 * else from the words. The top is midway between the last foggy hour's temperature and the first
 * fog-free hour's ([MorningFog.burnOffTemp]): the fog ended between them, and the air warms fast once it
 * has (Open-Meteo 2026-11-11: last fog 09:00 at 53.9°, clear 10:00 at 56.9° → 55.4°).
 */
object ForecastMorningFog {

    /** [date]'s 00:00–12:00 window. Null when the rows do not cover it. */
    fun forDate(rows: List<HourlyForecast>, date: LocalDate, zone: ZoneId): MorningFog? =
        forWindow(rows, MorningFogWindow.startMs(date, zone), MorningFogWindow.endMs(date, zone))

    /**
     * Today's fog still ahead: the window from the hour containing [nowMs] to noon. Null once the
     * window has passed or when the rows do not cover what is left of it.
     */
    fun aheadOf(rows: List<HourlyForecast>, date: LocalDate, zone: ZoneId, nowMs: Long): MorningFog? {
        val endMs = MorningFogWindow.endMs(date, zone)
        val startMs = maxOf(MorningFogWindow.startMs(date, zone), nowMs - nowMs % HOUR_MS)
        if (startMs >= endMs) return null
        return forWindow(rows, startMs, endMs)
    }

    fun forWindow(rows: List<HourlyForecast>, startMs: Long, endMs: Long): MorningFog? {
        if (!DailyHourlySummaries.windowCovered(rows, startMs, endMs)) return null
        val ordered = rows.distinctBy { it.dateTime }.sortedBy { it.dateTime }
        val inWindow = ordered.filter { it.dateTime in startMs until endMs }
        val foggy = inWindow.mapNotNull { hour -> hourFog(hour)?.let { hour to it } }
        if (foggy.isEmpty()) return MorningFog.NONE
        val last = foggy.last().first
        val firstClear = ordered.firstOrNull { it.dateTime > last.dateTime && hourFog(it) == null }
        return MorningFog(
            level = foggy.maxOf { it.second.first },
            topTempF = MorningFog.burnOffTemp(last.temperature, firstClear?.temperature ?: last.temperature),
            visibilityMi = foggy.mapNotNull { it.second.second }.minOrNull(),
        )
    }

    /** The hour's (level, visibility) when foggy, else null. */
    internal fun hourFog(hour: HourlyForecast): Pair<Int, Float?>? {
        val wordLevel = FogLevel.fromCondition(hour.condition)
        val visibility = hour.visibilityMi
        val foggyByVisibility = visibility != null && visibility <= FogLevel.FOG_VISIBILITY_MI
        if (wordLevel == FogLevel.NONE && !foggyByVisibility) return null
        val level = if (visibility != null) FogLevel.fromVisibility(visibility) else wordLevel
        return level to (visibility ?: FogLevel.standInVisibility(wordLevel))
    }

    private const val HOUR_MS = 3_600_000L
}
