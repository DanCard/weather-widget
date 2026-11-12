package com.weatherwidget.shared.fog

import java.time.LocalDate
import java.time.ZoneId

/**
 * A day's morning fog, as the daily bar draws it (plans/261111-daily-bar-morning-fog.md).
 *
 * [level] is one of the [FogLevel] codes. [FogLevel.NONE] is a real answer ("the window was covered and
 * there was no fog"), distinct from a null [MorningFog] ("not known"); a stored NONE is what lets a newer
 * fetch clear an older fetch's fog through `DailyHourlySummaries.carryForward`.
 *
 * [topTempF] is the burn-off temperature (°F): midway between the morning's last fog and the next hour or
 * report below it ([heightLevel]), which sets how far up the bar the fog reaches. [visibilityMi] is the lowest
 * visibility while foggy, which sets the opacity ([FogOpacity]). Both are null when [level] is NONE.
 * [predawn] is true when every foggy hour or report came before that day's sunrise: the fog was gone
 * before anyone saw it, so the ellipse draws petite ([FogPatch.PREDAWN_HEIGHT_SCALE]; user 2026-11-12,
 * "predawn fog [should be] petite. Not very tall. Maybe a third height? Not very wide either").
 * The four always move together.
 */
data class MorningFog(
    val level: Int,
    val topTempF: Float?,
    val visibilityMi: Float?,
    val predawn: Boolean = false,
) {
    val isFoggy: Boolean get() = level > FogLevel.NONE

    companion object {
        val NONE = MorningFog(FogLevel.NONE, null, null)

        /**
         * Where the fog's top sits: midway between the last foggy temperature and the first clear one.
         * The first clear reading alone overshoots — KNUQ 2026-11-09 warmed 59.0° → 62.6° in the 20
         * minutes after the fog — and the last foggy one alone draws a sliver.
         */
        fun burnOffTemp(lastFoggyF: Float, firstClearF: Float): Float = (lastFoggyF + firstClearF) / 2f

        /**
         * The least level that sets the fog's top, given the morning's strongest: the fog proper
         * ([FogLevel.FOG], ≤ 1 SM) on a morning that had it, else whatever the morning had. Trailing mist
         * still counts for the level and visibility but not the height: KNUQ 2026-11-09 was ¼ SM fog until
         * 09:15 at 55.4°, then 40 minutes of 2½–5 SM mist warming to 57.2° drew the ellipse to 58.1° —
         * half the bar for a few hours of fog (user: "Only lasted a couple of hours but the height makes
         * it look like it last much more"). plans/261111-fog-height-dense-top-bottom-weighted.md
         */
        fun heightLevel(strongestLevel: Int): Int = minOf(strongestLevel, FogLevel.FOG)

        /**
         * Rebuilds the stored columns; null when nothing was stored (the level column is null). A null
         * [predawn] (rows written before the column) reads as daylight fog, the size those rows drew at.
         */
        fun fromColumns(level: Int?, topTempF: Float?, visibilityMi: Float?, predawn: Boolean?): MorningFog? =
            level?.let { if (it <= FogLevel.NONE) NONE else MorningFog(it, topTempF, visibilityMi, predawn == true) }

        /** The stored predawn column: null when there is no fog to size. */
        fun predawnColumn(fog: MorningFog?): Boolean? = fog?.takeIf { it.isFoggy }?.predawn

        /**
         * Observed and forecast fog for one column. The stronger level wins, the top is the higher
         * burn-off and the visibility the lower; either side missing leaves the other. Pre-dawn only
         * when both sides are: any daylight fog draws full size.
         */
        fun combine(a: MorningFog?, b: MorningFog?): MorningFog? {
            if (a == null) return b
            if (b == null) return a
            if (!a.isFoggy) return b
            if (!b.isFoggy) return a
            return MorningFog(
                level = maxOf(a.level, b.level),
                topTempF = listOfNotNull(a.topTempF, b.topTempF).maxOrNull(),
                visibilityMi = listOfNotNull(a.visibilityMi, b.visibilityMi).minOrNull(),
                predawn = a.predawn && b.predawn,
            )
        }
    }
}

/** The morning window fog is read over: local midnight until [END_HOUR]:00. */
object MorningFogWindow {
    const val END_HOUR = 12

    fun startMs(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endMs(date: LocalDate, zone: ZoneId): Long = date.atTime(END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    /**
     * [date]'s sunrise at ([lat], [lon]) in epoch ms; null without a usable site (missing, NaN before the
     * first location fix, out of range) or with no sunrise (polar). Null leaves fog full size.
     */
    fun sunriseMs(date: LocalDate, lat: Double?, lon: Double?): Long? {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return com.weatherwidget.util.SunPositionUtils.sunriseInstant(date, lat, lon)?.toEpochMilli()
    }
}

/**
 * Fog strength codes, stored as integers. [HINT] is a station whose air was saturated with signs of
 * condensation but which did not report fog or mist itself; it draws faintest ([FogOpacity.HINT_ALPHA]).
 */
object FogLevel {
    const val NONE = 0
    const val HINT = 1
    const val LIGHT = 2
    const val FOG = 3
    const val DENSE = 4

    /** Visibility (statute miles) at or below which an hour counts as fog whatever its words say: 1 km. */
    const val FOG_VISIBILITY_MI = 0.62f

    /**
     * The level a condition word implies, for providers and observation rows with no visibility number.
     * Matches the degree words of the fog icons (plans/260408-degree-of-fog-icons.md). Haze and smoke
     * are not fog.
     */
    fun fromCondition(condition: String?): Int {
        val c = condition?.lowercase()?.trim() ?: return NONE
        if (c.isEmpty()) return NONE
        return when {
            // Provider words matched, never shown (`.contains`, which the localization guard reads as matching).
            c.contains("dense fog") -> DENSE
            c.contains("fog/mist") -> LIGHT // NWS's text for a METAR BR at any visibility
            c.contains("light fog") || c.contains("patchy fog") || c.contains("mist") -> LIGHT
            c.contains("fog") -> FOG
            else -> NONE
        }
    }

    /** Level from a measured visibility while in fog or mist. */
    fun fromVisibility(visibilityMi: Float): Int = when {
        visibilityMi < 0.25f -> DENSE
        visibilityMi <= 1f -> FOG
        else -> LIGHT
    }

    /** Stand-in visibility for a level known only from words: dense ⅛, fog ½, light 3 miles. */
    fun standInVisibility(level: Int): Float? = when (level) {
        DENSE -> 0.125f
        FOG -> 0.5f
        LIGHT -> 3f
        HINT -> 6f
        else -> null
    }
}

/**
 * How opaque the fog ellipse is (user, 2026-11-11: dynamic, from visibility): 15 % at 6 SM rising on a
 * log scale to [MAX_ALPHA] at ⅛ SM (raised 35 % → 50 % → 65 % → 80 % → 90 % at the user's request: dense fog read as wispy).
 * A [FogLevel.HINT] (saturated air, no fog reported) runs continuously from [HINT_ALPHA] at 10 SM to
 * [MIN_ALPHA] at 6 SM, so the whole range is one spectrum rather than fixed steps.
 */
object FogOpacity {
    const val MIN_ALPHA = 0.15f
    const val MAX_ALPHA = 0.90f
    const val HINT_ALPHA = 0.05f
    private const val HINT_CLEAR_MI = 10f
    private const val CLEAR_MI = 6f
    private const val DENSE_MI = 0.125f

    fun alpha(fog: MorningFog): Float {
        if (!fog.isFoggy) return 0f
        if (fog.level == FogLevel.HINT) {
            val v = (fog.visibilityMi ?: HINT_CLEAR_MI).coerceIn(CLEAR_MI, HINT_CLEAR_MI)
            return HINT_ALPHA + (HINT_CLEAR_MI - v) / (HINT_CLEAR_MI - CLEAR_MI) * (MIN_ALPHA - HINT_ALPHA)
        }
        val v = (fog.visibilityMi ?: FogLevel.standInVisibility(fog.level) ?: CLEAR_MI).coerceIn(DENSE_MI, CLEAR_MI)
        val t = (kotlin.math.ln(CLEAR_MI) - kotlin.math.ln(v)) / (kotlin.math.ln(CLEAR_MI) - kotlin.math.ln(DENSE_MI))
        return MIN_ALPHA + t * (MAX_ALPHA - MIN_ALPHA)
    }
}

/** Provider visibility in statute miles, one conversion for every parser. */
object VisibilityUnits {
    private const val METERS_PER_MILE = 1609.344f

    fun metersToMiles(meters: Number?): Float? =
        meters?.toFloat()?.takeIf { it.isFinite() && it >= 0f }?.let { it / METERS_PER_MILE }

    fun kilometersToMiles(km: Number?): Float? = km?.toDouble()?.let { metersToMiles(it * 1000.0) }

    fun miles(miles: Number?): Float? = miles?.toFloat()?.takeIf { it.isFinite() && it >= 0f }
}
