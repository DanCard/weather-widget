package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi

/**
 * Pure Kotlin decoder for METAR / SPECI reports and their remarks groups.
 *
 * Implements decoding for:
 * - High-precision T-group tenths (°C)
 * - 6-hour and 24-hour max/min temperature extremes
 * - Hourly and multi-hour precipitation amounts (Pxxxx, 6xxxx, 7xxxx)
 * - Sea level pressure (SLPxxx)
 * - Station automated diagnostics (AO1, AO2, $ maintenance flag)
 * - Body temperature/dewpoint, altimeter, report type, and sky cover
 */
object MetarDecoder {

    private const val INCHES_TO_MM = 25.4f

    enum class ReportType {
        METAR,
        SPECI,
        COR,
        UNKNOWN,
    }

    data class MetarReport(
        val rawReport: String,
        val reportType: ReportType,
        val stationId: String?,
        val isAuto: Boolean,
        val bodyTemperatureCelsius: Float?,
        val bodyDewpointCelsius: Float?,
        val altimeterInHg: Float?,
        val altimeterHpa: Float?,
        val skyLayers: List<NwsApi.CloudLayer>,
        val remarks: MetarRemarks?,
    )

    data class MetarRemarks(
        val rawRemarks: String,
        val isAutoStation: Boolean = false,
        val hasPrecipDiscriminator: Boolean = false,
        val maintenanceNeeded: Boolean = false,
        val seaLevelPressureHpa: Float? = null,
        val preciseTempCelsius: Float? = null,
        val preciseDewpointCelsius: Float? = null,
        val hourlyPrecipMm: Float? = null,
        val precip3or6HourMm: Float? = null,
        val precip24HourMm: Float? = null,
        val max6HourTempCelsius: Float? = null,
        val min6HourTempCelsius: Float? = null,
        val max24HourTempCelsius: Float? = null,
        val min24HourTempCelsius: Float? = null,
    )

    // Body tokens regexes
    private val TYPE_REGEX = Regex("""\b(METAR|SPECI|COR)\b""")
    private val STATION_REGEX = Regex("""\b([A-Z0-9]{4})\b""")
    private val AUTO_REGEX = Regex("""\bAUTO\b""")
    private val BODY_TEMP_DEWP_REGEX = Regex("""\b(M?\d{2})/(M?\d{2}|//)?\b""")
    private val ALTIMETER_INHG_REGEX = Regex("""\bA(\d{4})\b""")
    private val ALTIMETER_HPA_REGEX = Regex("""\bQ(\d{4})\b""")

    // Remarks tokens regexes
    private val T_GROUP_REGEX = Regex("""\bT([01])(\d{3})(?:([01])(\d{3}))?\b""")
    private val TEMP_6H_MAX_REGEX = Regex("""\b1([01])(\d{3})\b""")
    private val TEMP_6H_MIN_REGEX = Regex("""\b2([01])(\d{3})\b""")
    private val TEMP_24H_EXTREMES_REGEX = Regex("""\b4([01])(\d{3})([01])(\d{3})\b""")
    private val PRECIP_HOURLY_REGEX = Regex("""\bP(\d{4})\b""")
    private val PRECIP_3_6H_REGEX = Regex("""\b6(\d{4})\b""")
    private val PRECIP_24H_REGEX = Regex("""\b7(\d{4})\b""")
    private val SLP_REGEX = Regex("""\bSLP(\d{3})\b""")
    private val MAINTENANCE_REGEX = Regex("""(?:^|\s)\$(?=\s|$)""")

    /**
     * Decodes a raw METAR string into a structured [MetarReport].
     * Returns null if [raw] is null, blank, or "M" (missing sentinel).
     */
    fun decode(raw: String?): MetarReport? {
        val clean = raw?.trim() ?: return null
        if (clean.isEmpty() || clean.equals("M", ignoreCase = true)) return null

        val upper = clean.uppercase()
        val rmkIndex = upper.indexOf(" RMK ")
        val body = if (rmkIndex >= 0) upper.substring(0, rmkIndex).trim() else upper
        val rmk = if (rmkIndex >= 0) upper.substring(rmkIndex + 5).trim() else null

        val reportType = when {
            TYPE_REGEX.find(body)?.value == "SPECI" -> ReportType.SPECI
            TYPE_REGEX.find(body)?.value == "COR" -> ReportType.COR
            TYPE_REGEX.find(body)?.value == "METAR" -> ReportType.METAR
            else -> ReportType.UNKNOWN
        }

        val bodyTokens = body.split(Regex("""\s+"""))
        val stationId = bodyTokens.firstOrNull { token ->
            token.length == 4 && token != "AUTO" && token != "CORR" && token != "TEST" &&
                !TYPE_REGEX.matches(token) && token.all { it.isLetterOrDigit() }
        }

        val isAuto = AUTO_REGEX.containsMatchIn(body)

        // Body temp / dewpoint (e.g. 20/14 or M05/M12)
        val tempMatch = BODY_TEMP_DEWP_REGEX.find(body)
        val bodyTemp = tempMatch?.groupValues?.getOrNull(1)?.let(::parseBodyTemp)
        val bodyDewp = tempMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() && it != "//" }?.let(::parseBodyTemp)

        // Altimeter
        val altInHg = ALTIMETER_INHG_REGEX.find(body)?.groupValues?.get(1)?.toFloatOrNull()?.let { it / 100f }
        val altHpa = ALTIMETER_HPA_REGEX.find(body)?.groupValues?.get(1)?.toFloatOrNull()

        // Sky layers via MetarRawSkyParser
        val skyLayers = MetarRawSkyParser.layersFrom(upper)

        // Remarks
        val remarks = rmk?.let(::decodeRemarks)

        return MetarReport(
            rawReport = clean,
            reportType = reportType,
            stationId = stationId,
            isAuto = isAuto,
            bodyTemperatureCelsius = bodyTemp,
            bodyDewpointCelsius = bodyDewp,
            altimeterInHg = altInHg,
            altimeterHpa = altHpa,
            skyLayers = skyLayers,
            remarks = remarks,
        )
    }

    /**
     * Decodes the remarks segment (text after "RMK").
     */
    fun decodeRemarks(rawRemarks: String): MetarRemarks {
        val rmk = rawRemarks.trim().uppercase()
        if (rmk.isEmpty()) return MetarRemarks(rawRemarks = rawRemarks)

        val isAuto1 = Regex("""\bAO1\b""").containsMatchIn(rmk)
        val isAuto2 = Regex("""\bAO2\b""").containsMatchIn(rmk)
        val isAuto = isAuto1 || isAuto2
        val hasPrecipDisc = isAuto2
        // `\b` after `$` needs a following WORD character, so `\s\$\b` could never match a
        // space-delimited " $ " — only the trailing-$ branch ever fired. Lookaround instead:
        // the flag is its own token, at end-of-remarks or followed by whitespace.
        val maintenance = MAINTENANCE_REGEX.containsMatchIn(rmk)

        // T-group: T[s][TTT][s][DDD]
        var preciseTemp: Float? = null
        var preciseDewp: Float? = null
        val tMatch = T_GROUP_REGEX.find(rmk)
        if (tMatch != null) {
            val tSign = tMatch.groupValues[1]
            val tDigits = tMatch.groupValues[2]
            preciseTemp = parseTGroupDigits(tSign, tDigits)

            val dSign = tMatch.groupValues.getOrNull(3)
            val dDigits = tMatch.groupValues.getOrNull(4)
            if (!dSign.isNullOrEmpty() && !dDigits.isNullOrEmpty()) {
                preciseDewp = parseTGroupDigits(dSign, dDigits)
            }
        }

        // 6-hour max/min
        val max6h = TEMP_6H_MAX_REGEX.find(rmk)?.let {
            parseTGroupDigits(it.groupValues[1], it.groupValues[2])
        }
        val min6h = TEMP_6H_MIN_REGEX.find(rmk)?.let {
            parseTGroupDigits(it.groupValues[1], it.groupValues[2])
        }

        // 24-hour extremes: 4[s][TTT][s][TTT]
        var max24h: Float? = null
        var min24h: Float? = null
        val extremes24hMatch = TEMP_24H_EXTREMES_REGEX.find(rmk)
        if (extremes24hMatch != null) {
            max24h = parseTGroupDigits(extremes24hMatch.groupValues[1], extremes24hMatch.groupValues[2])
            min24h = parseTGroupDigits(extremes24hMatch.groupValues[3], extremes24hMatch.groupValues[4])
        }

        // Precipitation
        val hourlyPrecip = PRECIP_HOURLY_REGEX.find(rmk)?.groupValues?.get(1)?.toIntOrNull()?.let {
            (it / 100f) * INCHES_TO_MM
        }
        val precip3or6h = PRECIP_3_6H_REGEX.find(rmk)?.groupValues?.get(1)?.toIntOrNull()?.let {
            (it / 100f) * INCHES_TO_MM
        }
        val precip24h = PRECIP_24H_REGEX.find(rmk)?.groupValues?.get(1)?.toIntOrNull()?.let {
            (it / 100f) * INCHES_TO_MM
        }

        // SLP: SLPxxx
        val slpHpa = SLP_REGEX.find(rmk)?.groupValues?.get(1)?.toIntOrNull()?.let { slp ->
            val dec = slp / 10f
            if (slp >= 500) 900f + dec else 1000f + dec
        }

        return MetarRemarks(
            rawRemarks = rawRemarks,
            isAutoStation = isAuto,
            hasPrecipDiscriminator = hasPrecipDisc,
            maintenanceNeeded = maintenance,
            seaLevelPressureHpa = slpHpa,
            preciseTempCelsius = preciseTemp,
            preciseDewpointCelsius = preciseDewp,
            hourlyPrecipMm = hourlyPrecip,
            precip3or6HourMm = precip3or6h,
            precip24HourMm = precip24h,
            max6HourTempCelsius = max6h,
            min6HourTempCelsius = min6h,
            max24HourTempCelsius = max24h,
            min24HourTempCelsius = min24h,
        )
    }

    private fun parseBodyTemp(token: String): Float? {
        val clean = token.trim()
        val isNegative = clean.startsWith("M")
        val numStr = if (isNegative) clean.substring(1) else clean
        val intVal = numStr.toIntOrNull() ?: return null
        return if (isNegative) -intVal.toFloat() else intVal.toFloat()
    }

    private fun parseTGroupDigits(signChar: String, digitsStr: String): Float? {
        val num = digitsStr.toIntOrNull() ?: return null
        val value = num / 10f
        return if (signChar == "1") -value else value
    }
}
