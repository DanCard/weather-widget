package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi

/**
 * Extracts METAR sky-condition layers from a **raw METAR string**.
 *
 * The NWS `/observations` endpoint hands us sky condition pre-parsed as a `cloudLayers` array, so
 * [NwsApi.parseCloudLayers] covers that path. Synoptic — the web fallback — does not: it returns the
 * report itself in `metar_set_1`, and the parser that reads its timeseries used to take only
 * `air_temp_set_1`. Every web-fallback row therefore stored no sky condition at all, which is how
 * KNUQ (3.8 km, the nearest official station) contributed zero cloud on 2026-08-21 while the curve
 * was fed from KSJC 15.9 km away.
 *
 * Output feeds [MetarSkyCover] unchanged, so the two paths express sky condition identically.
 */
object MetarRawSkyParser {

    /**
     * Sky groups carrying a height, in hundreds of feet: `OVC012` = overcast at 1,200 ft.
     *
     * The trailing edge is a negative lookahead rather than `\b`, because `\b` needs a word
     * character on one side and cannot fire after the `///` of an unreadable height — `BKN///`
     * silently failed to match while `BKN012` matched.
     */
    private val LAYER_GROUP = Regex("""\b(FEW|SCT|BKN|OVC|VV)(\d{3}|///)(?![A-Z0-9])""")

    /** Height-less codes meaning "no cloud detected". A `CLR` report carries no base by definition. */
    private val CLEAR_GROUP = Regex("""\b(CLR|SKC|NCD|CAVOK)\b""")

    private const val FEET_PER_GROUP = 100.0
    private const val METERS_PER_FOOT = 0.3048

    /**
     * Layers in report order, or empty when the report carries no sky condition ("not reported" —
     * never "clear"; [MetarSkyCover] draws that distinction and must be allowed to).
     *
     * Remarks are cut first. Everything after `RMK` is free-form and routinely repeats sky-like
     * tokens (`RMK AO2 SLP176 BKN999`), so reading them as layers invents cloud that the observer
     * did not report.
     */
    fun layersFrom(raw: String?): List<NwsApi.CloudLayer> {
        val report = raw?.trim()?.uppercase() ?: return emptyList()
        // Synoptic writes a bare "M" for a missing value.
        if (report.isEmpty() || report == "M") return emptyList()

        val body = report.substringBefore(" RMK ")

        val layers = LAYER_GROUP.findAll(body).map { match ->
            val amount = match.groupValues[1]
            val height = match.groupValues[2]
            NwsApi.CloudLayer(
                amount = amount,
                // An unreadable height ("///") keeps the layer with an unknown base rather than
                // dropping it: MetarSkyCover admits unknown-height layers to the low read on
                // purpose, because discarding them would hide real low cloud.
                baseMeters = height.toIntOrNull()?.let { it * FEET_PER_GROUP * METERS_PER_FOOT },
            )
        }.toList()

        if (layers.isNotEmpty()) return layers

        // A clear code is only meaningful when no layer group was reported alongside it.
        return CLEAR_GROUP.find(body)
            ?.let { listOf(NwsApi.CloudLayer(amount = it.groupValues[1], baseMeters = null)) }
            ?: emptyList()
    }
}
