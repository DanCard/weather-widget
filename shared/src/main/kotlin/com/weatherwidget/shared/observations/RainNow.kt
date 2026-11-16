package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.WeatherCodeMapper
import com.weatherwidget.shared.actuals.ObservedPrecip
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.util.Log

/**
 * Is it raining here now? Drives the header's rain-now dots (`plans/261115-header-rain-now-dots.md`;
 * user, 2026-11-15: "I love option E", "I like the idea that radar turns on rain dots. Tomorrow.io
 * should only turn it on if we are viewing tomorrow api or source is tomorrow.io").
 *
 * Three inputs, the strongest wins, any one turns the dots on:
 *  1. **Station**: the nearest official station's newest report ([stationLevel]).
 *  2. **Radar**: the NOAA mosaic pixel at the site ([RadarNowSample], sampled by [RadarNowSampler]).
 *  3. **Tomorrow.io**: its 5-minute history, only while Tomorrow.io is the displayed source.
 *
 * Model "Rain" labels are never an input: on 2026-11-15 OWM said `Rain` at 14:10 while every gauge
 * read dry.
 */
object RainNow {
    const val LOG_TAG = "RAIN_NOW"

    enum class Level { SPRINKLING, RAINING, HEAVY }

    /** A station report older than this says nothing: hourly METAR plus one late report. */
    const val STATION_MAX_AGE_MS = 75 * 60_000L

    /** Tolerated clock skew for a report stamped in the future. */
    const val FUTURE_SKEW_MS = 5 * 60_000L

    /** A Tomorrow.io 5-minute row older than this says nothing. */
    const val TOMORROW_MAX_AGE_MS = 30 * 60_000L

    /** Late-run allowance on top of the interval (job scheduling jitter, a slow request). */
    const val RADAR_SLACK_MS = 5 * 60_000L

    /**
     * A radar sample older than this says nothing: the screen-on battery run interval (20 min, the
     * longest regular screen-on gap) plus [RADAR_SLACK_MS], so radar-only dots do not blink off
     * between two runs (a 15-min limit went dark 5 min in every 20 on battery). The 66–69 % band's
     * 45-min runs can still leave gaps; a stale radar reading there says nothing on purpose.
     */
    const val RADAR_MAX_AGE_MS =
        com.weatherwidget.shared.util.CurrentTempCadence.BATTERY_SCREEN_ON_MINUTES * 60_000L + RADAR_SLACK_MS


    /** Rows from these feeds are station reports; Tomorrow.io's and model currents are not. */
    private val STATION_APIS = setOf(WeatherSource.NWS.id, WeatherSource.METAR.id, WeatherSource.SYNOPTIC.id)

    data class Verdict(
        val level: Level?,
        val station: Level?,
        val radar: Level?,
        val tomorrow: Level?,
        /** The station report that decided the station input, null when none could speak. */
        val stationReading: ObservationReading?,
        val radarSample: RadarNowSample?,
        val tomorrowReading: ObservationReading?,
    ) {
        /** `level=RAINING station=KNUQ@3.8km 20:55 -RA→RAINING radar=14,214,20,255 age=3m→RAINING tomorrow=off`. */
        fun describe(nowMs: Long): String {
            val s = stationReading?.let { r ->
                "${r.stationId}@${"%.1f".format(r.distanceKm)}km age=${(nowMs - r.timestamp) / 60_000}m " +
                    "wx=${decisiveText(r)}→${station ?: "dry"}"
            } ?: "none"
            val rad = radarSample?.let { "${it.rgbaText()} age=${(nowMs - it.sampledAtMs) / 60_000}m→${radar ?: "none"}" } ?: "none"
            val t = tomorrowReading?.let { "'${it.condition.trim()}' age=${(nowMs - it.timestamp) / 60_000}m→${tomorrow ?: "dry"}" } ?: "off"
            return "level=${level ?: "off"} station=$s radar=$rad tomorrow=$t"
        }
    }

    fun resolve(
        readings: List<ObservationReading>,
        radar: RadarNowSample?,
        displaySourceId: String,
        nowMs: Long,
    ): Verdict {
        val stationReading = stationReport(readings, nowMs)
        val station = stationReading?.let(::stationLevel)
        val freshRadar = radar?.takeIf { nowMs - it.sampledAtMs in -FUTURE_SKEW_MS..RADAR_MAX_AGE_MS }
        val radarLevel = freshRadar?.level()
        val tomorrowReading = if (displaySourceId == WeatherSource.TOMORROW_IO.id) tomorrowRow(readings, nowMs) else null
        val tomorrow = tomorrowReading?.let { tomorrowLevel(it.condition) }
        val level = listOfNotNull(station, radarLevel, tomorrow).maxOrNull()
        return Verdict(level, station, radarLevel, tomorrow, stationReading, freshRadar, tomorrowReading)
    }

    /** A row that can say whether a station sees rain falling. */
    internal fun canSpeak(reading: ObservationReading, nowMs: Long): Boolean =
        reading.api in STATION_APIS &&
            reading.stationType == StationType.OFFICIAL &&
            !reading.qcFailed &&
            reading.stationId != NwsBlend.STATION_ID &&
            reading.stationId != TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID &&
            reading.distanceKm <= ObservedPrecip.MAX_STATION_KM &&
            reading.timestamp > nowMs - STATION_MAX_AGE_MS &&
            reading.timestamp <= nowMs + FUTURE_SKEW_MS &&
            (!reading.rawMetar.isNullOrBlank() || reading.condition.isNotBlank())

    /** The nearest speaking station's newest speaking report: showers are local. */
    internal fun stationReport(readings: List<ObservationReading>, nowMs: Long): ObservationReading? {
        val speaking = readings.filter { canSpeak(it, nowMs) }
        val nearest = speaking.minByOrNull { it.distanceKm }?.stationId ?: return null
        return speaking.filter { it.stationId == nearest }.maxByOrNull { it.timestamp }
    }

    /** One report's rain: its raw METAR when it has one (KPAO 17:57 said `VCSH` with condition " Rain"), else its words. */
    fun stationLevel(reading: ObservationReading): Level? {
        val report = reading.rawMetar?.takeIf { it.isNotBlank() }?.let(MetarDecoder::decode)
        return if (report != null) metarLevel(report.presentWeather) else conditionLevel(reading.condition)
    }

    /** `+RA` heavy, `-RA`/`RA`/`SHRA`/`FZRA`/`UP` raining, `DZ` sprinkling; `VC*` and snow say nothing. */
    fun metarLevel(presentWeather: List<String>): Level? =
        presentWeather.filterNot { it.startsWith("VC") }.mapNotNull { group ->
            val heavy = group.startsWith("+")
            val rain = "RA" in group || "UP" in group
            val drizzle = "DZ" in group
            when {
                rain && (heavy || group.contains("TS")) -> Level.HEAVY
                drizzle && heavy -> Level.HEAVY
                rain -> Level.RAINING
                drizzle -> Level.SPRINKLING
                else -> null
            }
        }.maxOrNull()

    /** An NWS text description ("Light Rain and Fog/Mist", "Drizzle", "Showers in Vicinity"). */
    fun conditionLevel(condition: String): Level? {
        val c = condition.trim().lowercase()
        if (c.isEmpty() || "vicinity" in c || "snow" in c || "ice" in c || "hail" in c) return null
        return when {
            "heavy" in c && ("rain" in c || "drizzle" in c || "shower" in c) -> Level.HEAVY
            "thunderstorm" in c && ("rain" in c || "shower" in c) -> Level.HEAVY
            "rain" in c || "shower" in c -> Level.RAINING
            "drizzle" in c -> Level.SPRINKLING
            else -> null
        }
    }

    private fun tomorrowRow(readings: List<ObservationReading>, nowMs: Long): ObservationReading? =
        readings.filter {
            it.stationId == TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID &&
                it.timestamp > nowMs - TOMORROW_MAX_AGE_MS &&
                it.timestamp <= nowMs + FUTURE_SKEW_MS &&
                it.condition.isNotBlank()
        }.maxByOrNull { it.timestamp }

    /**
     * Tomorrow.io's stored condition, by the weather code it came from ([WeatherCodeMapper]): drizzle
     * 4000 sprinkling; rain 4001, freezing rain 6001 and thunderstorm 8000 raining; heavy rain 4201
     * heavy. Keyed through the mapper so the words cannot drift from what is stored.
     */
    private val TOMORROW_LEVELS: Map<String, Level> = mapOf(
        4000 to Level.SPRINKLING,
        4001 to Level.RAINING,
        6001 to Level.RAINING,
        8000 to Level.RAINING,
        4201 to Level.HEAVY,
    ).mapKeys { WeatherCodeMapper.tomorrowIoCodeToCondition(it.key) }

    fun tomorrowLevel(condition: String): Level? = TOMORROW_LEVELS[condition.trim()]

    private fun decisiveText(reading: ObservationReading): String {
        val wx = reading.rawMetar?.takeIf { it.isNotBlank() }?.let(MetarDecoder::decode)?.presentWeather
        return if (wx != null) wx.joinToString(" ").ifEmpty { "none" } else "'${reading.condition.trim()}'"
    }

    @Volatile private var lastLogged: Level? = null
    @Volatile private var logged = false

    /** [LOG_TAG] once per change of level in this process (off→on, level→level, on→off); every render logs its icon already. */
    fun logTransition(verdict: Verdict, where: String, nowMs: Long) {
        if (logged && lastLogged == verdict.level) return
        logged = true
        lastLogged = verdict.level
        Log.d(LOG_TAG, "where=$where ${verdict.describe(nowMs)}")
    }
}
