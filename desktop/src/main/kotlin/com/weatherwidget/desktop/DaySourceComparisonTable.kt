package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DataScreenStyle
import com.weatherwidget.shared.graph.DaySourceComparison
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Desktop SQLite reads for the shared [DaySourceComparison] runner. */
internal class DesktopDaySourceComparisonHost(
    private val dao: DesktopWeatherDao,
    private val sources: List<WeatherSource>,
    /** 30-day high bias by source id, from the accuracy stats the window already computed. */
    private val biasBySource: Map<String, Double>,
) : DaySourceComparison.Host {
    override suspend fun visibleSources(): List<WeatherSource> = sources

    override suspend fun forecastRows(targetDateEpoch: Long, lat: Double, lon: Double): List<DaySourceComparison.StoredRow> =
        dao.getForecastRowsForDate(targetDateEpoch, lat, lon)

    override suspend fun dailyHistory(targetDateEpoch: Long, lat: Double, lon: Double): List<DailyHistory> =
        dao.getExtremesInRange(targetDateEpoch, targetDateEpoch, lat, lon)

    override suspend fun hourly(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceIds: List<String>): List<HourlyForecast> =
        sourceIds.flatMap { dao.getHourlyForecasts(lat, lon, it, startMs, endMs) }

    override suspend fun highBias(sources: List<WeatherSource>, lat: Double, lon: Double): Map<String, Double> =
        biasBySource.filterKeys { id -> sources.any { it.id == id } }
}

/**
 * "All sources for <day>": the [DaySourceComparison] rows, with Android's columns and short names
 * (`ForecastComparisonActivity.renderSourceComparison`). The viewed source is bold; an age past
 * [DaySourceComparison.STALE_AGE_MS] is dimmed. Clicking a header sorts by that column
 * ([DaySourceComparison.Sort.tapped]); the sorted header carries the arrow.
 */
@Composable
internal fun DaySourceComparisonTable(
    date: LocalDate,
    rows: List<DaySourceComparison.Row>,
    viewedSource: WeatherSource,
    useCelsius: Boolean,
    nowMs: Long,
    sort: DaySourceComparison.Sort = DaySourceComparison.Sort.DEFAULT,
    onSortTap: (DaySourceComparison.SortColumn) -> Unit = {},
    canApplyOrder: Boolean = false,
    onApplyOrder: (List<DaySourceComparison.Row>) -> Unit = {},
) {
    val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val monthName = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val sorted = DaySourceComparison.sort(rows, sort)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "All sources for $dayName $monthName ${date.dayOfMonth}",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onApplyOrder(sorted) },
            enabled = canApplyOrder,
            shape = RoundedCornerShape(DataScreenStyle.CARD_RADIUS_DP.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ObsStyle.sourceButtonFill,
                contentColor = ObsStyle.accent,
                disabledContainerColor = ObsStyle.sourceButtonFill.copy(alpha = 0.4f),
                disabledContentColor = ObsStyle.accent.copy(alpha = 0.4f),
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.heightIn(min = 30.dp).testTag("source_compare_apply_order"),
        ) {
            Text(
                "Set API order",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    DataCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            val cells = sorted.map { DaySourceComparison.cells(it, nowMs, useCelsius) }
            val header = HEADERS.mapIndexed { i, h ->
                DaySourceComparison.headerLabel(h, DaySourceComparison.SortColumn.entries[i], sort)
            }
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current.density
            val baseStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            val layout = DaySourceComparison.layout(maxWidth.value, DaySourceComparison.MIN_COLUMN_GAP_DP, header, cells) { text, bold ->
                val style = baseStyle.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
                textMeasurer.measure(text, style, maxLines = 1, softWrap = false).size.width / density
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TableRow(
                    "source_compare_header",
                    layout.headers(header),
                    layout, color = ObsStyle.textSecondary, bold = false, staleAge = false,
                    onCellClick = { column -> onSortTap(DaySourceComparison.SortColumn.entries[column]) },
                )
                cells.forEachIndexed { i, c ->
                    TableRow(
                        "source_compare_row_$i",
                        layout.values(c),
                        layout,
                        color = Color.White,
                        bold = sorted[i].source == viewedSource,
                        staleAge = c.ageIsStale,
                    )
                }
                Text(
                    legendText(layout.showAmount, DaySourceComparison.showStarNote(cells)),
                    modifier = Modifier.padding(top = 5.dp).testTag("source_compare_legend"),
                    color = ObsStyle.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

/** One line per column, "Rain highest hourly chance, 8am–8pm", names bold (Android: `sourceComparisonLegend`). */
internal fun legendText(showAmount: Boolean, showStar: Boolean): AnnotatedString = buildAnnotatedString {
    DaySourceComparison.legendColumns(showAmount).forEachIndexed { i, column ->
        if (i > 0) append("\n")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(HEADERS[column.ordinal]) }
        append(" ")
        append(LEGEND_TEXT.getValue(column))
    }
    // Only while some Age carries the star (DaySourceComparison.Row.noLongerSent).
    if (showStar) {
        append("\n")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(DaySourceComparison.AGE_STAR) }
        append(" ")
        append(STAR_NOTE)
    }
}

/** Desktop wording of the Age star's note; Android: `source_compare_legend_star`. */
internal const val STAR_NOTE = "The source no longer sends today (its days run on UTC); the age is from its last forecast that did."

/** Desktop legend wording; Android carries the same in its `source_compare_legend_*` strings. */
internal val LEGEND_TEXT: Map<DaySourceComparison.SortColumn, String> = mapOf(
    DaySourceComparison.SortColumn.DAY_RAIN to "highest hourly chance, 8am–8pm",
    DaySourceComparison.SortColumn.PEAK to "hour of the highest daytime chance",
    DaySourceComparison.SortColumn.NIGHT_RAIN to "highest hourly chance, 8pm–8am",
    DaySourceComparison.SortColumn.AMOUNT to "forecast total, 8am–8pm",
    DaySourceComparison.SortColumn.CLOUD to "cloud cover at noon",
    DaySourceComparison.SortColumn.BIAS to "average forecast high minus actual, last 30 days (+ = ran warm)",
    DaySourceComparison.SortColumn.AGE to "time since the source last confirmed this forecast; grey after 24 h",
)

private val HEADERS = listOf("Source", "High", "Low", "Rain", "Peak", "Night", "Amount", "Cloud", "Bias", "Age")

/** One table row: [values] are the [layout]'s shown columns; cell tags and [onCellClick] use the shared column index. */
@Composable
private fun TableRow(
    tag: String,
    values: List<String>,
    layout: DaySourceComparison.Layout,
    color: Color,
    bold: Boolean,
    staleAge: Boolean,
    onCellClick: ((Int) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().testTag(tag)) {
        values.forEachIndexed { i, v ->
            val column = layout.columns[i]
            val isAge = column == DaySourceComparison.SortColumn.AGE.ordinal
            Text(
                v,
                modifier = Modifier.width(layout.widths[i].dp)
                    .then(if (onCellClick != null) Modifier.clickable { onCellClick(column) }.testTag("${tag}_$column") else Modifier),
                color = if (isAge && staleAge) ObsStyle.textSecondary else color,
                fontSize = 12.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                maxLines = 1,
            )
        }
    }
}
