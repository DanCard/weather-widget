package com.weatherwidget.shared.observations

/**
 * Physical and structural sanity checks on a METAR temperature, run against the raw report.
 *
 * This is *our* opinion of a reading, complementing the two upstream opinions we already store in
 * the same `qcFailed` column: NWS's per-field code ([NwsQualityControl]) and Synoptic's QC flags
 * ([SynopticApi][com.weatherwidget.data.remote.SynopticApi]). It exists because a corrupt report can
 * arrive with a clean upstream verdict — on 2026-08-31 Synoptic flagged KPAO's garbled 16:47 report
 * while the `api=NWS` web-fallback copy of the *identical METAR string* was stored unflagged, and a
 * 50 °F station 5 km out dragged the blended actual line ~5 °F below every real neighbour.
 *
 * Every rule here rejects something **impossible**, never something merely unusual. That is a
 * deliberate line. The tempting rule — "the temperature jumped 20 °F in an hour, distrust it" —
 * was measured against 7,229 stored reports and rejected: as a rate it fires 1,096 times on
 * ordinary evening cooling, and a Bay Area marine push or gust front really does move temperature
 * that fast (the KPAO report that motivated this carries `G22KT` gusts). A check that cannot
 * distinguish real weather from corruption is worse than no check, because it silently deletes
 * observations. These rules need no history, no state, and no window: they are pure functions of a
 * single reading, and on that same 7,229-row corpus they flagged exactly three rows, all three
 * genuinely corrupt.
 */
object MetarPlausibility {

    /**
     * Widest range any surface station on Earth can legitimately report, with margin.
     * World records are roughly −129 °F (Vostok) and 134 °F (Furnace Creek).
     */
    const val MIN_PLAUSIBLE_F: Float = -90f
    const val MAX_PLAUSIBLE_F: Float = 140f

    /** Stable, log-friendly reason codes. */
    const val REASON_OUT_OF_RANGE: String = "out_of_range"
    const val REASON_MALFORMED_TEMP_GROUP: String = "malformed_temp_group"
    const val REASON_DEWPOINT_ABOVE_TEMP: String = "dewpoint_above_temp"

    data class Verdict(val failed: Boolean, val reason: String? = null)

    private val PASS = Verdict(failed = false)

    /**
     * A bare `digits/digits` token. In a METAR body this shape is only ever the temperature/dewpoint
     * group: visibility fractions carry their unit (`1/2SM`), and RVR groups start with a letter
     * (`R28L/2600FT`), so neither can match a whole token here.
     */
    private val SLASH_GROUP = Regex("""^(M?\d+)/(M?\d+|/+)$""")

    /** A well-formed METAR temperature or dewpoint field: two digits, optionally `M`-negated. */
    private val VALID_FIELD = Regex("""^M?\d{2}$""")

    /**
     * @param temperatureFahrenheit the temperature actually about to be stored — which may come from
     *   the provider's own decode rather than from [rawMetar], and is exactly the value that would
     *   enter the blend.
     * @param rawMetar the raw report, when one travelled with the reading. Null or blank means there
     *   is nothing to cross-check, and only the range rule applies — absence of a report is not
     *   evidence of a bad reading, the same stance [NwsQualityControl] takes on a missing code.
     */
    fun check(temperatureFahrenheit: Float, rawMetar: String?): Verdict {
        if (temperatureFahrenheit.isNaN() ||
            temperatureFahrenheit < MIN_PLAUSIBLE_F ||
            temperatureFahrenheit > MAX_PLAUSIBLE_F
        ) {
            return Verdict(failed = true, reason = REASON_OUT_OF_RANGE)
        }

        val body = bodyOf(rawMetar) ?: return PASS
        val match = body.split(WHITESPACE)
            .firstNotNullOfOrNull { SLASH_GROUP.find(it) }
            ?: return PASS

        val tempField = match.groupValues[1]
        val dewpointField = match.groupValues[2]

        // A three-digit temperature field is not a METAR temperature. Seen 2026-08-27 as KRHV's
        // `209/14`, where the upstream decoder salvaged the trailing `09` -> 9 °C -> 48.2 °F and we
        // inherited it, between neighbouring reports of 66.2 °F and 73.4 °F. Our own MetarDecoder
        // finds no match in that string at all, so the dewpoint rule below can never see it — this
        // rule is the only thing that catches the shape.
        if (!VALID_FIELD.matches(tempField)) {
            return Verdict(failed = true, reason = REASON_MALFORMED_TEMP_GROUP)
        }
        val isMissingDewpoint = dewpointField.all { it == '/' }
        if (!isMissingDewpoint && !VALID_FIELD.matches(dewpointField)) {
            return Verdict(failed = true, reason = REASON_MALFORMED_TEMP_GROUP)
        }
        if (isMissingDewpoint) return PASS

        // Dewpoint is by definition the temperature at which the parcel saturates, so it cannot
        // exceed the air temperature. Equality is legitimate and common (fog, saturated air).
        val temp = parseField(tempField) ?: return PASS
        val dewpoint = parseField(dewpointField) ?: return PASS
        if (dewpoint > temp) {
            return Verdict(failed = true, reason = REASON_DEWPOINT_ABOVE_TEMP)
        }
        return PASS
    }

    private val WHITESPACE = Regex("""\s+""")

    /** The report body — remarks are excluded, since coded RMK groups are not `T/Td` pairs. */
    private fun bodyOf(rawMetar: String?): String? {
        val clean = rawMetar?.trim()?.uppercase()
        if (clean.isNullOrEmpty() || clean == "M") return null
        val rmk = clean.indexOf(" RMK ")
        return if (rmk >= 0) clean.substring(0, rmk) else clean
    }

    private fun parseField(field: String): Int? {
        val negative = field.startsWith("M")
        val digits = if (negative) field.substring(1) else field
        val value = digits.toIntOrNull() ?: return null
        return if (negative) -value else value
    }
}
