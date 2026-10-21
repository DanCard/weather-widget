package com.weatherwidget.shared.nowcast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether Open-Meteo has a **native** 15-minute model at a site, or only hourly output interpolated
 * to 15 minutes (which looks like a nowcast and is not one).
 *
 * Learned from a probe, not a map of model domains: [NATIVE_MODELS] is request formation only. The
 * answer is kept [MAX_AGE_MS] and then asked again, so a domain that grows or shrinks is picked up.
 * Measured 2026-10-21: Kyiv → `{"error":true,"reason":"No data is available for this location"}`;
 * Berlin/Paris → suffixed series per model; SF → one **unsuffixed** `precipitation` (a single model
 * answering is not suffixed).
 */
object NowcastCoverage {
    const val NATIVE_MODELS =
        "icon_d2,ncep_hrrr_conus,meteofrance_arome_france_hd,knmi_harmonie_arome_europe,dmi_harmonie_arome_europe"
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True when any native model returned a precipitation value; false for Open-Meteo's
     * "No data is available" error or an all-null reply; null when [response] is not a probe reply
     * at all (unknown — the caller asks again next time).
     */
    fun parseProbe(response: String): Boolean? {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return null
        if (root["error"]?.jsonPrimitive?.booleanOrNull == true) {
            return if (root["reason"]?.jsonPrimitive?.content?.contains("No data", ignoreCase = true) == true) false else null
        }
        val minutely = root["minutely_15"]?.jsonObject ?: return null
        return minutely.entries
            .filter { (key, _) -> key.startsWith("precipitation") }
            .any { (_, values) ->
                (values as? JsonArray)?.any { it !is JsonNull && it.jsonPrimitive.floatOrNull != null } == true
            }
    }
}
