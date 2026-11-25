package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi

/**
 * Derives a short human-readable condition label (e.g. `"Clear"`, `"Partly Cloudy"`,
 * `"Light Rain"`, `"Fog"`) from a decoded METAR report and/or its sky-cover layers.
 *
 * Used by [MetarObservationMapper] (whose Aviation Weather / NWS METAR text feeds carry no
 * `textDescription`) and as a blank-description fallback in [NwsObservationMapper], so API and
 * METAR/web-fallback cards render consistent condition text on both platforms.
 *
 * Rules:
 * 1. Present-weather groups take precedence over sky cover (a report of `-RA OVC012` is
 *    `"Light Rain"`, not `"Overcast"`).
 * 2. When no present weather is reported, the highest-coverage sky layer maps to a standard
 *    label (`CLR`/`SKC`/`NCD`/`CAVOK` → `"Clear"`, `FEW` → `"Few Clouds"`, `SCT` → `"Partly Cloudy"`,
 *    `BKN` → `"Mostly Cloudy"`, `OVC`/`VV` → `"Overcast"`).
 * 3. When neither present weather nor sky layers are reported, returns `""` — never invents
 *    `"Clear"` from silence.
 */
object MetarConditionFormatter {

    private val WX_GROUP_REGEX = Regex(
        """^(?<intensity>-|\+|VC)?(?<descriptor>MI|PR|BC|DR|BL|SH|TS|FZ)?(?<phenomena>(?:DZ|RA|SN|SG|IC|PL|GR|GS|UP|BR|FG|FU|VA|DU|SA|HZ|PY|PO|SQ|FC|SS|DS)+)?$""",
    )

    private val PHENOMENA = mapOf(
        "DZ" to "Drizzle",
        "RA" to "Rain",
        "SN" to "Snow",
        "SG" to "Snow Grains",
        "IC" to "Ice Crystals",
        "PL" to "Ice Pellets",
        "GR" to "Hail",
        "GS" to "Small Hail",
        "UP" to "Precipitation",
        "BR" to "Mist",
        "FG" to "Fog",
        "FU" to "Smoke",
        "VA" to "Volcanic Ash",
        "DU" to "Dust",
        "SA" to "Sand",
        "HZ" to "Haze",
        "PY" to "Spray",
        "PO" to "Dust Whirls",
        "SQ" to "Squalls",
        "FC" to "Funnel Cloud",
        "SS" to "Sandstorm",
        "DS" to "Duststorm",
    )

    private val SKY_RANK = mapOf(
        "CLR" to (1 to "Clear"),
        "SKC" to (1 to "Clear"),
        "NCD" to (1 to "Clear"),
        "CAVOK" to (1 to "Clear"),
        "FEW" to (2 to "Few Clouds"),
        "SCT" to (3 to "Partly Cloudy"),
        "BKN" to (4 to "Mostly Cloudy"),
        "OVC" to (5 to "Overcast"),
        "VV" to (5 to "Overcast"),
    )

    fun format(report: MetarDecoder.MetarReport?, layers: List<NwsApi.CloudLayer>): String {
        val weather = report?.presentWeather
            .orEmpty()
            .mapNotNull(::formatWeatherToken)
            .distinct()
        if (weather.isNotEmpty()) {
            return weather.joinToString(", ")
        }

        val bestSky = layers
            .mapNotNull { SKY_RANK[it.amount.trim().uppercase()] }
            .maxByOrNull { it.first }
        return bestSky?.second.orEmpty()
    }

    internal fun formatWeatherToken(rawToken: String): String? {
        val token = rawToken.trim().uppercase()
        if (token.isEmpty()) return null
        val match = WX_GROUP_REGEX.matchEntire(token) ?: return null
        val intensity = match.groups["intensity"]?.value
        val descriptor = match.groups["descriptor"]?.value
        val phenomenaRaw = match.groups["phenomena"]?.value.orEmpty()
        val phenomena = phenomenaRaw.chunked(2).mapNotNull { PHENOMENA[it] }

        if (descriptor == null && phenomena.isEmpty()) return null

        val core = when (descriptor) {
            "TS" -> if (phenomena.isEmpty()) "Thunderstorm" else "Thunderstorm & ${phenomena.joinToString(" & ")}"
            "SH" -> if (phenomena.isEmpty()) "Showers" else "${phenomena.joinToString(" & ")} Showers"
            "FZ" -> if (phenomena.isEmpty()) "Freezing" else "Freezing ${phenomena.joinToString(" & ")}"
            "MI" -> if (phenomena.isEmpty()) "Shallow" else "Shallow ${phenomena.joinToString(" & ")}"
            "PR" -> if (phenomena.isEmpty()) "Partial" else "Partial ${phenomena.joinToString(" & ")}"
            "BC" -> if (phenomena.isEmpty()) "Patchy" else "Patchy ${phenomena.joinToString(" & ")}"
            "DR" -> if (phenomena.isEmpty()) "Drifting" else "Drifting ${phenomena.joinToString(" & ")}"
            "BL" -> if (phenomena.isEmpty()) "Blowing" else "Blowing ${phenomena.joinToString(" & ")}"
            else -> phenomena.joinToString(" & ")
        }
        if (core.isEmpty()) return null

        return when (intensity) {
            "-" -> "Light $core"
            "+" -> "Heavy $core"
            "VC" -> "$core Nearby"
            else -> core
        }
    }
}
