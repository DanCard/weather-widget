package com.weatherwidget.data.remote

import com.weatherwidget.shared.observations.MetarDecoder
import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * NWS's own latest-METAR text file, `tgftp.nws.noaa.gov/data/observations/metar/stations/{ID}.TXT`:
 * the NWS observation "web" leg on both platforms. No key, no token, `Cache-Control: no-cache`.
 *
 * The user's rule (2026-10-15): NWS actuals come from NWS servers only, and "web" means a plain fetch
 * with no token — never Synoptic (what `weather.gov/wrh/timeseries` loads in the browser) and never
 * aviationweather.gov (a stale CDN answer showed KNUQ 12:15 at 12:40, after this file had 12:35 at
 * 12:38). plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md.
 *
 * Two lines: `yyyy/MM/dd HH:mm` (UTC), then the raw report. Only stations that send METARs have a
 * file; any other ID redirects to a directory that 404s, which is [FetchOutcome.NoData].
 */
open class NwsMetarTextApi @Inject constructor(
    private val httpClient: HttpClient,
) {
    open suspend fun fetchLatest(stationId: String): FetchOutcome<AviationWeatherApi.MetarRow> =
        try {
            val response = httpClient.get("$BASE_URL/${stationId.uppercase()}.TXT")
            when (val status = response.status.value) {
                in 200..299 -> parse(response.bodyAsText(), stationId)
                301, 302, 404 -> FetchOutcome.NoData
                else -> FetchOutcome.Failed.of("HTTP $status")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "metar text failed for $stationId", e)
            FetchOutcome.failed(e)
        }

    companion object {
        private const val TAG = "NwsMetarTextApi"
        const val BASE_URL = "https://tgftp.nws.noaa.gov/data/observations/metar/stations"

        private val HEADER_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        private val HEADER_REGEX = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}$""")
        private val TIME_GROUP_REGEX = Regex("""\b(\d{2})(\d{2})(\d{2})Z\b""")

        /**
         * The file → one [AviationWeatherApi.MetarRow], the shape the METAR mapper already takes.
         * Temperature is the T-group tenths when the station sends them, else the whole-degree body
         * group. A report without a temperature, or a body that is not a METAR for [stationId], is
         * [FetchOutcome.NoData] / [FetchOutcome.Failed] respectively — never a 0 °C reading.
         */
        fun parse(body: String, stationId: String): FetchOutcome<AviationWeatherApi.MetarRow> {
            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val header = lines.firstOrNull()?.takeIf { HEADER_REGEX.matches(it) }
                ?: return FetchOutcome.Failed.of("unexpected body for $stationId")
            // A long report can wrap onto further lines.
            val raw = lines.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                ?: return FetchOutcome.NoData
            val decoded = MetarDecoder.decode(raw) ?: return FetchOutcome.NoData
            if (!decoded.stationId.equals(stationId, ignoreCase = true)) {
                return FetchOutcome.Failed.of("report is for ${decoded.stationId}, not $stationId")
            }
            val headerTime = LocalDateTime.parse(header, HEADER_FORMAT).atOffset(ZoneOffset.UTC)
            val observedAtMs = observedAtMillis(raw, headerTime.toInstant().toEpochMilli())
            val tempC = decoded.remarks?.preciseTempCelsius ?: decoded.bodyTemperatureCelsius
                ?: return FetchOutcome.NoData
            return FetchOutcome.Success(
                AviationWeatherApi.MetarRow(
                    stationId = stationId.uppercase(),
                    stationName = stationId.uppercase(),
                    observedAtMillis = observedAtMs,
                    // Unused: the NWS web row is filed at the site, at NWS's own station distance.
                    latitude = 0.0,
                    longitude = 0.0,
                    elevationMeters = null,
                    temperatureCelsius = tempC,
                    dewpointCelsius = decoded.remarks?.preciseDewpointCelsius ?: decoded.bodyDewpointCelsius,
                    seaLevelPressureHpa = decoded.remarks?.seaLevelPressureHpa,
                    // Empty = "use the raw report's layers" in MetarObservationMapper.
                    cloudLayers = emptyList(),
                    rawOb = raw,
                    isSpeci = decoded.reportType == MetarDecoder.ReportType.SPECI,
                ),
            )
        }

        /**
         * The report's own `ddHHmmZ` group, placed in the month of the header's date (stepping back a
         * month when the day is ahead of it, e.g. a `31…Z` report under a header on the 1st). The
         * header is the file's write time, so it stands in only when the report has no time group.
         */
        internal fun observedAtMillis(raw: String, headerMs: Long): Long {
            val match = TIME_GROUP_REGEX.find(raw) ?: return headerMs
            val (day, hour, minute) = match.destructured.toList().map { it.toInt() }
            val header = java.time.Instant.ofEpochMilli(headerMs).atOffset(ZoneOffset.UTC)
            var month = header.toLocalDate().withDayOfMonth(1)
            repeat(2) {
                if (day <= month.lengthOfMonth()) {
                    val candidate = month.withDayOfMonth(day).atTime(hour, minute).atOffset(ZoneOffset.UTC)
                    if (!candidate.isAfter(header.plusDays(1))) return candidate.toInstant().toEpochMilli()
                }
                month = month.minusMonths(1)
            }
            return headerMs
        }
    }
}
