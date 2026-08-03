package com.weatherwidget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.actuals.BlendTable
import com.weatherwidget.shared.actuals.BlendTableFormatter
import com.weatherwidget.shared.actuals.BlendTableRow
import com.weatherwidget.util.StationHistoryUrl

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
 * Column widths are weights rather than fixed dp so the table still reads at the window's 500dp
 * default and expands usefully when the user widens it.
 */
private object BlendCol {
    const val STATION = 1.0f
    // Wide enough for the "type" HEADER, not the one-letter cell it sits over.
    const val TYPE = 0.45f
    const val KM = 0.95f
    const val LAST_READ = 0.85f
    const val AGE = 0.6f
    const val RAW = 0.66f
    const val VALUE = 1.15f
    const val SHARE = 0.85f
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
internal fun BlendTableView(tables: List<BlendTable>, sourceId: String) {
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
            BlendTableCard(table, sourceId)
        }
    }
}

@Composable
private fun BlendTableCard(table: BlendTable, sourceId: String) {
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
            // The reported symptom, called out where it happens rather than left for the reader to
            // spot by scanning the raw column.
            if (table.outsideStationRange) {
                Text(
                    "outside station range",
                    color = ObsStyle.error,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = RULE_COLOR_STRONG,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[0], BlendCol.STATION)
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[1], BlendCol.TYPE)
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[2], BlendCol.KM, TextAlign.End, endPadding = 12)
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[3], BlendCol.LAST_READ)
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[4], BlendCol.AGE, TextAlign.End, endPadding = 12)
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[5], BlendCol.RAW, TextAlign.End, endPadding = 12)
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[6], BlendCol.VALUE)
            HeaderCell(BlendTableFormatter.COLUMN_HEADERS[7], BlendCol.SHARE, TextAlign.End)
        }

        HorizontalDivider(thickness = 1.dp, color = RULE_COLOR_STRONG)

        table.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = RULE_COLOR)
            BlendRow(row, sourceId)
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

@Composable
private fun BlendRow(row: BlendTableRow, sourceId: String) {
    val valueColor = if (row.isExtrapolated) EXTRAPOLATED_TINT else Color.White
    // Same affordance as the Observations tab: NWS stations link to their public time-series page.
    val historyUrl = StationHistoryUrl.forStation(sourceId, row.station)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = historyUrl != null) { historyUrl?.let(::openInBrowser) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        DataCell(
            row.station,
            BlendCol.STATION,
            if (historyUrl != null) ObsStyle.accent else Color.White,
            FontWeight.Medium,
        )
        DataCell(
            row.type,
            BlendCol.TYPE,
            if (row.type == "OFFICIAL") ObsStyle.typeOfficial else ObsStyle.typePersonal,
        )
        DataCell(row.km, BlendCol.KM, ObsStyle.textSecondary, align = TextAlign.End, endPadding = 12)
        DataCell(row.lastRead, BlendCol.LAST_READ, ObsStyle.timeReported)
        DataCell(row.age, BlendCol.AGE, ObsStyle.textSecondary, align = TextAlign.End, endPadding = 12)
        DataCell(row.raw, BlendCol.RAW, Color.White, align = TextAlign.End, endPadding = 12)
        DataCell(row.valueFedToBlend, BlendCol.VALUE, valueColor)
        DataCell(row.weightShare, BlendCol.SHARE, ObsStyle.textSecondary, align = TextAlign.End)
    }
}

@Composable
private fun RowScope.HeaderCell(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.Start,
    endPadding: Int = 4,
) {
    Text(
        text,
        color = ObsStyle.textSecondary,
        fontSize = 18.9.sp,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        maxLines = 2,
        modifier = Modifier.weight(weight).padding(end = endPadding.dp),
    )
}

@Composable
private fun RowScope.DataCell(
    text: String,
    weight: Float,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
    endPadding: Int = 4,
) {
    Text(
        text,
        color = color,
        fontSize = 27.3.sp,
        fontWeight = fontWeight,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.weight(weight).padding(end = endPadding.dp),
    )
}
