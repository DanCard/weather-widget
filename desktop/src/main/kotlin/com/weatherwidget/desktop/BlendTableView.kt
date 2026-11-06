package com.weatherwidget.desktop

import com.weatherwidget.data.model.StationType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.actuals.BlendTable
import kotlin.math.ceil
import com.weatherwidget.shared.actuals.BlendTableFormatter
import com.weatherwidget.shared.actuals.BlendTableRow

/**
 * The "Blend" tab: for each blended point, the per-station table showing what each station actually
 * measured (`raw`) versus what was fed into the weighted average, and how much of the result each one
 * owns.
 *
 * Exists because the graph's observed dot can read higher than every station in the Observations tab
 * and there was no way to see why. A stale station is carried to the target time by the *forecast's*
 * change over the gap ([ActualTemperatureSeriesBuilder.extrapolateForward]), so on a fast-warming
 * morning a station 30 minutes behind contributes degrees nobody measured — while still counting as
 * "observed". This table makes exactly that visible: compare the `raw` column against
 * `value fed to blend`.
 *
 * Columns are sized to their content, like Android's `TableLayout` (WeatherObservationsActivity):
 * each is as wide as its widest cell or its header on at most two lines, and the width left over is
 * shared out as equal gaps between the columns. Fixed weights left a wide hole after the one-letter
 * type, clipped "45m" in the age column, and wrapped the "type" header at the 500dp default
 * (2026-11-06). When the window is too narrow, the station column gives way and ellipsizes.
 */
/** Merged onto the theme's [LocalTextStyle] so measuring and drawing use the same font. */
private class BlendStyles(base: TextStyle) {
    val header = base.merge(TextStyle(fontSize = 18.9.sp, fontWeight = FontWeight.Bold))
    val cell = base.merge(TextStyle(fontSize = 27.3.sp, fontWeight = FontWeight.Normal))
    val station = cell.merge(TextStyle(fontWeight = FontWeight.Medium))

    fun forColumn(column: Int) = if (column == STATION_COLUMN) station else cell
}

/** The narrowest gap between two columns; any width to spare widens every gap equally (SpaceBetween). */
private val MIN_COLUMN_GAP = 8.dp

private const val STATION_COLUMN = 0

/** Cells in [BlendTableFormatter.COLUMN_HEADERS] order. */
private fun BlendTableRow.cells(): List<String> =
    listOf(station, type, km, lastRead, age, raw, valueFedToBlend, weightShare)

/**
 * The text's full width in whole px, rounded UP: `TextLayoutResult.size` rounds, and a column a
 * fraction too narrow ellipsized "68.4%" to "68.4…".
 */
private fun TextMeasurer.widthPx(text: String, style: TextStyle): Int =
    ceil(measure(text, style, softWrap = false, maxLines = 1).multiParagraph.maxIntrinsicWidth).toInt()

/**
 * The narrowest a header can be on at most two lines (its `maxLines`): the best single split at a
 * space, or the whole text when it is one word.
 */
private fun headerWidthPx(measurer: TextMeasurer, styles: BlendStyles, header: String): Int {
    val words = header.split(' ')
    fun widthOf(text: String) = measurer.widthPx(text, styles.header)
    if (words.size == 1) return widthOf(header)
    return (1 until words.size).minOf { split ->
        maxOf(widthOf(words.take(split).joinToString(" ")), widthOf(words.drop(split).joinToString(" ")))
    }
}

/** Each column's content width in px: its widest cell or its header, whichever is wider. */
private fun contentWidthsPx(measurer: TextMeasurer, styles: BlendStyles, table: BlendTable): List<Int> =
    BlendTableFormatter.COLUMN_HEADERS.mapIndexed { column, header ->
        val widestCell = table.rows.maxOfOrNull { row ->
            measurer.widthPx(row.cells()[column], styles.forColumn(column))
        } ?: 0
        maxOf(headerWidthPx(measurer, styles, header), widestCell)
    }

/**
 * Fits the content widths into [availableWidth]: the station column shrinks (ellipsizing) first when
 * they do not fit with [MIN_COLUMN_GAP] between them, never below [stationFloor] (its header's
 * width) — a table without station names is no use. Below that the row runs past the right edge;
 * at the 2× font that starts under ~570dp. The rows lay these widths out with
 * [Arrangement.SpaceBetween], which shares what is left as equal gaps — exactly, where gaps computed
 * here and rounded to px per cell overran the row by a few px and ellipsized the last column.
 */
internal fun fitBlendColumns(contentWidths: List<Dp>, availableWidth: Dp, stationFloor: Dp = 0.dp): List<Dp> {
    val gaps = contentWidths.size - 1
    val others = contentWidths.filterIndexed { i, _ -> i != STATION_COLUMN }.fold(0.dp) { a, b -> a + b }
    val stationRoom = availableWidth - others - MIN_COLUMN_GAP * gaps
    val stationContent = contentWidths[STATION_COLUMN]
    val station = minOf(stationContent, maxOf(stationRoom, minOf(stationFloor, stationContent))).coerceAtLeast(0.dp)
    return contentWidths.mapIndexed { i, w -> if (i == STATION_COLUMN) station else w }
}

private val EXTRAPOLATED_TINT = Color(0xFFE8A24E) // amber — same "derived, not measured" cue as timeReported

/**
 * Hairline rules between rows. Deliberately barely-there: the table is scanned column-wise (raw vs
 * fed-to-blend), so the rules only need to stop the eye drifting a row — anything stronger competes
 * with the amber/white value colouring that carries the actual meaning.
 */
private val RULE_COLOR = Color.White.copy(alpha = 0.07f)
private val RULE_COLOR_STRONG = Color.White.copy(alpha = 0.14f)

@Composable
internal fun BlendTableView(tables: List<BlendTable>) {
    if (tables.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No blended points in range.",
                color = ObsStyle.textSecondary,
                fontSize = 11.2.sp,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(tables) { table ->
            BlendTableCard(table)
        }
    }
}

@Composable
private fun BlendTableCard(table: BlendTable) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(ObsStyle.cardFill, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(table.timeLabel, color = ObsStyle.accent, fontSize = 33.6.sp, fontWeight = FontWeight.Bold)
            Text(
                "  →  ${table.blendedLabel}",
                color = Color.White,
                fontSize = 33.6.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "  ${table.stationCount} stations",
                color = ObsStyle.textSecondary,
                fontSize = 21.sp,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = RULE_COLOR_STRONG,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val base = LocalTextStyle.current
        val styles = remember(base) { BlendStyles(base) }
        val contentWidths = remember(table, density, styles) {
            with(density) { contentWidthsPx(measurer, styles, table).map { it.toDp() } }
        }
        val stationFloor = remember(density, styles) {
            with(density) { headerWidthPx(measurer, styles, BlendTableFormatter.COLUMN_HEADERS[STATION_COLUMN]).toDp() }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widths = fitBlendColumns(contentWidths, maxWidth, stationFloor)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("blend_header"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BlendTableFormatter.COLUMN_HEADERS.forEachIndexed { column, header ->
                        HeaderCell(header, widths[column], styles.header, columnAlign(column))
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = RULE_COLOR_STRONG)

                table.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(thickness = 1.dp, color = RULE_COLOR)
                    BlendRow(row, widths, styles)
                }
            }
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = RULE_COLOR_STRONG,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        BlendTableFormatter.LEGEND.forEach { legendLine ->
            Text(
                legendLine,
                color = ObsStyle.textSecondary,
                fontSize = 15.4.sp,
            )
        }
    }
}

private fun columnAlign(column: Int): TextAlign =
    if (column in BlendTableFormatter.NUMERIC_COLUMNS) TextAlign.End else TextAlign.Start

@Composable
private fun BlendRow(row: BlendTableRow, widths: List<Dp>, styles: BlendStyles) {
    val valueColor = if (row.isExtrapolated) EXTRAPOLATED_TINT else Color.White
    // Same affordance as the Observations tab: NWS stations link to their public time-series page.
    // Decided in :shared from the row's own provider, so Android and desktop link the same rows.
    val historyUrl = row.historyUrl
    val colors = listOf(
        if (historyUrl != null) ObsStyle.accent else Color.White,
        if (row.stationType == StationType.OFFICIAL) ObsStyle.typeOfficial else ObsStyle.typePersonal,
        ObsStyle.textSecondary,
        ObsStyle.timeReported,
        ObsStyle.textSecondary,
        Color.White,
        valueColor,
        ObsStyle.textSecondary,
    )
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = historyUrl != null) { historyUrl?.let(::openInBrowser) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        row.cells().forEachIndexed { column, text ->
            DataCell(
                text,
                widths[column],
                colors[column],
                styles.forColumn(column),
                columnAlign(column),
            )
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, style: TextStyle, align: TextAlign) {
    Text(
        text,
        color = ObsStyle.textSecondary,
        style = style,
        textAlign = align,
        maxLines = 2,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun DataCell(text: String, width: Dp, color: Color, style: TextStyle, align: TextAlign) {
    Text(
        text,
        color = color,
        style = style,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
    )
}
