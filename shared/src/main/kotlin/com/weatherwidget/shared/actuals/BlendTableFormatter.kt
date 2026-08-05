package com.weatherwidget.shared.actuals

import com.weatherwidget.shared.util.TempUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats a [BlendBreakdown] into the "Blend" tab's table.
 *
 * Pure and platform-free so Android and desktop render byte-identical text from the same numbers —
 * the whole point of the tab is to be trustworthy about what the blend did, which it cannot be if the
 * two platforms format it independently. Keeping it a pure function also makes it unit-testable
 * without a UI harness (see testing-strategy: prefer pure-function extraction over mocking).
 */
data class BlendTableRow(
    val station: String,
    val type: String,
    val km: String,
    val lastRead: String,
    /** How stale that reading was at the blended timestamp — the driver of the extrapolation. */
    val age: String,
    val raw: String,
    /** e.g. `65.00 R` / `67.05 E` — the kind is carried inline as a [BlendTableFormatter.LEGEND] code. */
    val valueFedToBlend: String,
    val weightShare: String,
    /** Drives row tinting: these degrees came from the forecast, not a thermometer. */
    val isExtrapolated: Boolean,
)

data class BlendTable(
    val timeLabel: String,
    val blendedLabel: String,
    val stationCount: Int,
    val rows: List<BlendTableRow>,
)

data class DominantTempAgeRows(
    val temperature: String,
    val age: String,
)

object BlendTableFormatter {

    // Kept short on purpose: at the tab's ~2x font these headers, not the cells, set the column
    // widths, and the spelled-out versions pushed the last column off a phone-width screen.
    val COLUMN_HEADERS = listOf(
        "station", "type", "km", "last read", "age", "raw", "fed to blend", "weight",
    )

    /** Indices of [COLUMN_HEADERS] that hold numbers and therefore right-align. */
    val NUMERIC_COLUMNS = setOf(2, 4, 5, 7)

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Single letters, explained by [LEGEND].
     *
     * The tab renders at roughly double the app's normal body size so the numbers are readable at a
     * glance. At that size the spelled-out words ("PERSONAL", "extrapolated") push the seven columns
     * past the window width and every cell wraps mid-word, destroying the column alignment the whole
     * table depends on. One letter plus a key costs one line and buys the font size back.
     */
    fun kindLabel(sourceKind: String): String = when (sourceKind) {
        "observed" -> "R"
        "interpolated" -> "I"
        "forecast_extrapolated" -> "E"
        else -> sourceKind
    }

    /** See [kindLabel]. */
    fun typeLabel(stationType: String): String = when (stationType) {
        "OFFICIAL" -> "O"
        "PERSONAL" -> "P"
        else -> stationType
    }

    /** Integer-minute convention shared by the Blend tab and compact graph annotations. */
    fun formatAgeMs(ageMs: Long): String = "${ageMs.coerceAtLeast(0L) / 60_000L}m"

    /** Compact dominant-station rows; temperature and age match that station's raw Blend-table cells. */
    fun formatDominantTempAgeRows(
        stationReadingTempF: Float,
        ageMs: Long,
        useCelsius: Boolean,
    ): DominantTempAgeRows =
        DominantTempAgeRows(
            temperature = requireNotNull(TempUtils.formatTemp(stationReadingTempF, useCelsius)),
            age = formatAgeMs(ageMs),
        )

    /** Uses the raw-reading and age columns belonging to the selected dominant contribution. */
    fun formatDominantTempAgeRows(
        contribution: DominantBlendContribution,
        useCelsius: Boolean,
    ): DominantTempAgeRows =
        formatDominantTempAgeRows(
            stationReadingTempF = contribution.rawTemp,
            ageMs = contribution.ageMs,
            useCelsius = useCelsius,
        )

    /**
     * Key for the single-letter [typeLabel] / [kindLabel] codes. Rendered under the table.
     *
     * `I` (interpolated) is deliberately absent. The tab shows only the newest blended point, and
     * [ActualTemperatureSeriesBuilder] returns `interpolated` only for a station holding readings on
     * BOTH sides of the target time — but every observation timestamp is itself a candidate, so a
     * later reading would *be* the newest point. `I` is therefore unreachable here short of a
     * future-dated observation row, and keying a code that never appears is just noise.
     */
    val LEGEND = listOf(
        "type:  O = official station   P = personal (backyard) station",
        "value: R = real reading   E = extrapolated from forecast",
    )

    fun format(
        breakdown: BlendBreakdown,
        useCelsius: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): BlendTable {
        fun temp(f: Float): Float = if (useCelsius) TempUtils.fahrenheitToCelsius(f) else f
        fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zoneId).format(TIME)

        // Nearest first, matching the Observations tab's own ordering so the two lists can be read
        // side by side station-for-station. (Weight share is the more "explanatory" order, but it
        // reshuffles between timestamps and makes cross-tab comparison harder.)
        val rows = breakdown.contributions
            .sortedBy { it.distanceKm }
            .map { c ->
                BlendTableRow(
                    station = c.stationId,
                    type = typeLabel(c.stationType),
                    km = String.format(Locale.US, "%.2f", c.distanceKm),
                    lastRead = time(c.lastReadingMs),
                    // Minutes throughout: weight reaches zero at 180 min, so the number stays small
                    // and comparable, and mixed h/m units would hide how close a row is to that cliff.
                    age = formatAgeMs(c.ageMs),
                    raw = String.format(Locale.US, "%.1f", temp(c.rawTemp)),
                    valueFedToBlend = String.format(
                        Locale.US,
                        "%.2f %s",
                        temp(c.resolvedTemp),
                        kindLabel(c.sourceKind),
                    ),
                    weightShare = String.format(Locale.US, "%.1f%%", c.weightShare * 100.0),
                    isExtrapolated = c.sourceKind == "forecast_extrapolated",
                )
            }

        return BlendTable(
            timeLabel = time(breakdown.targetMs),
            blendedLabel = String.format(Locale.US, "%.2f°", temp(breakdown.blendedTemp)),
            stationCount = breakdown.contributions.size,
            rows = rows,
        )
    }

    fun format(
        breakdowns: List<BlendBreakdown>,
        useCelsius: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<BlendTable> = breakdowns.map { format(it, useCelsius, zoneId) }

    /**
     * The same tables as fixed-width text, for Android's monospace view (and for pasting into a bug
     * report). Column widths are measured from the content so the numeric columns line up.
     */
    fun renderText(tables: List<BlendTable>): String {
        if (tables.isEmpty()) return "No blended points in range."

        val alignRight = NUMERIC_COLUMNS
        val cells = { row: BlendTableRow ->
            listOf(
                row.station, row.type, row.km, row.lastRead, row.age,
                row.raw, row.valueFedToBlend, row.weightShare,
            )
        }
        val widths = COLUMN_HEADERS.indices.map { column ->
            maxOf(
                COLUMN_HEADERS[column].length,
                tables.flatMap { it.rows }.maxOfOrNull { cells(it)[column].length } ?: 0,
            )
        }

        fun line(values: List<String>): String = values
            .mapIndexed { column, value ->
                if (column in alignRight) value.padStart(widths[column]) else value.padEnd(widths[column])
            }
            .joinToString("  ")
            .trimEnd()

        val header = line(COLUMN_HEADERS)
        val rule = "-".repeat(header.length)

        return tables.joinToString("\n\n") { table ->
            buildString {
                append("${table.timeLabel}  ->  ${table.blendedLabel}   ${table.stationCount} stations")
                append("\n")
                append(header).append("\n")
                append(rule).append("\n")
                table.rows.forEach { append(line(cells(it))).append("\n") }
            }.trimEnd()
        } + "\n\n" + LEGEND.joinToString("\n")
    }
}
