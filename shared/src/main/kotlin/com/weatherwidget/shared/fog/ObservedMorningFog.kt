package com.weatherwidget.shared.fog

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.observations.MetarDecoder
import com.weatherwidget.shared.observations.NwsBlend
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * A day's observed morning fog from **one actuals provider's** readings (the caller passes the rows
 * whose `api` is the source's resolved provider; plans/261111-daily-bar-morning-fog.md).
 *
 * Per hour of the 00:00–12:00 window, the nearest station that reported in that hour stands for it, as
 * observed rain does. A report with raw METAR text is foggy when (user, 2026-11-11, "I like adding both
 * rules"):
 *  1. the station flags fog or mist (`FG`, `BR`, `MIFG`/`BCFG`/`PRFG`) at any visibility, or reports
 *     visibility of fog (≤ [FogLevel.FOG_VISIBILITY_MI]) without precipitation; or
 *  2. its air is saturated (temperature = dewpoint at the precision reported) with wind ≤ 5 kt **and**
 *     a sign of condensation: visibility under 10 SM or cloud at or below 500 ft. Without that sign KNUQ
 *     `00000KT 10SM CLR 13/13` (2026-11-11 00:15) counted on a clear night. Such a report is only a
 *     [FogLevel.HINT].
 * Any other report (a model analysis, an NWS text-only row) is read from its condition word.
 *
 * The top is midway between the last report at the morning's height level ([MorningFog.heightLevel]:
 * fog proper when there was any) and the next report below it ([MorningFog.burnOffTemp]), or the latest
 * report's while the fog has not cleared. A saturation hint never prolongs a morning that had real fog,
 * and trailing mist never raises a morning that had fog.
 */
object ObservedMorningFog {

    /** Calm-enough wind for the saturation rule. */
    const val MAX_SATURATION_WIND_KT = 5

    /** Visibility below which saturation counts: the sensor saw something. */
    const val SATURATION_VISIBILITY_MI = 10f

    /** A cloud base at or below this (500 ft) counts as condensation at the ground. */
    const val SATURATION_CLOUD_BASE_M = 152.4

    /**
     * [date]'s fog from [readings]: null when no reading in the window can speak to fog (none at all,
     * or only personal stations with neither a condition nor a raw report), [MorningFog.NONE] when the
     * stations that could saw none.
     */
    fun forDate(readings: List<ObservationReading>, date: LocalDate, zone: ZoneId): MorningFog? {
        val startMs = MorningFogWindow.startMs(date, zone)
        val endMs = MorningFogWindow.endMs(date, zone)
        val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sequence = nearestPerHour(readings.filter { canSpeakToFog(it) && it.timestamp in startMs until dayEndMs })
        val inWindow = sequence.filter { it.timestamp < endMs }
        if (inWindow.isEmpty()) return null

        val raw = sequence.map { it to reportFog(it) }
        // On a morning with real (flagged) fog, saturation hints do not prolong it: KNUQ 2026-11-09 read
        // `8SM FEW004 15/15` at 10:15, 20 minutes after its last mist, and the hint carried the fog to
        // 10:35 at 62.6° (user: "Fog only lasted till around 10 am").
        val hasRealFog = raw.any { (reading, fog) -> fog != null && fog.first > FogLevel.HINT && reading.timestamp < endMs }
        val judged = if (hasRealFog) raw.map { (r, fog) -> r to fog?.takeIf { it.first > FogLevel.HINT } } else raw
        val foggy = judged.filter { (reading, fog) -> fog != null && reading.timestamp < endMs }
        if (foggy.isEmpty()) return MorningFog.NONE
        val level = foggy.maxOf { it.second!!.first }
        // The top comes from the fog proper; trailing mist does not raise it (MorningFog.heightLevel).
        val heightLevel = MorningFog.heightLevel(level)
        fun setsHeight(fog: Pair<Int, Float?>?) = fog != null && fog.first >= heightLevel
        val lastFoggyIndex = judged.indexOfLast { (reading, fog) -> setsHeight(fog) && reading.timestamp < endMs }
        val lastFoggy = judged[lastFoggyIndex].first
        val firstClear = judged.drop(lastFoggyIndex + 1).firstOrNull { !setsHeight(it.second) }?.first

        val strongest = foggy.filter { it.second!!.first > FogLevel.HINT }.ifEmpty { foggy }
        return MorningFog(
            level = level,
            topTempF = MorningFog.burnOffTemp(lastFoggy.temperature, firstClear?.temperature ?: judged.last().first.temperature),
            visibilityMi = strongest.mapNotNull { it.second!!.second }.minOrNull(),
        )
    }

    /** False for rows that say nothing about fog: QC failures, the blend, conditionless personal stations. */
    internal fun canSpeakToFog(reading: ObservationReading): Boolean =
        !reading.qcFailed &&
            reading.stationId != NwsBlend.STATION_ID &&
            (!reading.rawMetar.isNullOrBlank() || reading.condition.isNotBlank())

    /** Per hour, the readings of the nearest station that reported in it, in time order. */
    internal fun nearestPerHour(readings: List<ObservationReading>): List<ObservationReading> =
        readings.groupBy { it.timestamp / HOUR_MS }
            .toSortedMap()
            .values
            .flatMap { hour ->
                val nearest = hour.minBy { it.distanceKm }.stationId
                hour.filter { it.stationId == nearest }.sortedBy { it.timestamp }
            }

    /** The report's (level, visibility) when foggy, else null. */
    internal fun reportFog(reading: ObservationReading): Pair<Int, Float?>? {
        val report = reading.rawMetar?.takeIf { it.isNotBlank() }?.let(MetarDecoder::decode)
            ?: return FogLevel.fromCondition(reading.condition)
                .takeIf { it > FogLevel.NONE }
                ?.let { it to FogLevel.standInVisibility(it) }
        return metarFog(report)
    }

    /** Both station rules on a decoded report. */
    fun metarFog(report: MetarDecoder.MetarReport): Pair<Int, Float?>? {
        val visibility = report.visibilityStatuteMiles
        val flagged = report.presentWeather.filterNot { it.startsWith("VC") }
        val fogFlag = flagged.firstOrNull { "FG" in it }
        val mistFlag = flagged.any { "BR" in it }
        if (fogFlag != null) {
            // Shallow, patchy or partial fog (MIFG, BCFG, PRFG) is light whatever the prevailing visibility.
            val partial = fogFlag.contains("MI") || fogFlag.contains("BC") || fogFlag.contains("PR")
            val level = when {
                partial -> FogLevel.LIGHT
                visibility != null -> maxOf(FogLevel.FOG, FogLevel.fromVisibility(visibility))
                else -> FogLevel.FOG
            }
            return level to (visibility ?: FogLevel.standInVisibility(level))
        }
        if (mistFlag) {
            val level = visibility?.let(FogLevel::fromVisibility) ?: FogLevel.LIGHT
            return level to (visibility ?: FogLevel.standInVisibility(level))
        }
        // Fog by visibility alone: KPAO 2026-11-11 06:47 `1/16SM VV001 12/12` wrote no FG. Not when
        // precipitation is what cuts the visibility.
        val precipitating = flagged.any { w -> PRECIP_CODES.any { it in w } }
        if (visibility != null && visibility <= FogLevel.FOG_VISIBILITY_MI && !precipitating) {
            return FogLevel.fromVisibility(visibility) to visibility
        }
        return if (saturatedWithCondensation(report)) FogLevel.HINT to visibility else null
    }

    private val PRECIP_CODES = listOf("RA", "SN", "DZ", "PL", "GR", "GS", "SG", "IC", "UP")

    internal fun saturatedWithCondensation(report: MetarDecoder.MetarReport): Boolean {
        val temp = report.bestTemperatureCelsius ?: return false
        val dew = report.bestDewpointCelsius ?: return false
        if (abs(temp - dew) > 0.05f) return false
        val wind = report.windSpeedKt ?: return false
        if (wind > MAX_SATURATION_WIND_KT) return false
        val lowVisibility = report.visibilityStatuteMiles?.let { it < SATURATION_VISIBILITY_MI } ?: false
        val groundCloud = report.skyLayers.any { layer -> layer.baseMeters?.let { it <= SATURATION_CLOUD_BASE_M } ?: false }
        return lowVisibility || groundCloud
    }

    private const val HOUR_MS = 3_600_000L
}
