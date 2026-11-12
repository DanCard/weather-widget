package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
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
 * (`ForecastHistoryActivity.renderSourceComparison`). The viewed source is bold; an age past
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
) {
    val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val monthName = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    Text("All sources for $dayName $monthName ${date.dayOfMonth}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
    val sorted = DaySourceComparison.sort(rows, sort)
    DataCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            val cells = sorted.map { DaySourceComparison.cells(it, nowMs, useCelsius) }
            val showAmount = DaySourceComparison.showAmountColumn(maxWidth.value, cells)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TableRow(
                    "source_compare_header",
                    HEADERS.mapIndexed { i, h ->
                        DaySourceComparison.headerLabel(h, DaySourceComparison.SortColumn.entries[i], sort)
                    },
                    showAmount, color = ObsStyle.textSecondary, bold = false, staleAge = false,
                    onCellClick = { i -> onSortTap(DaySourceComparison.SortColumn.entries[i]) },
                )
                cells.forEachIndexed { i, c ->
                    TableRow(
                        "source_compare_row_$i",
                        c.values(),
                        showAmount,
                        color = Color.White,
                        bold = sorted[i].source == viewedSource,
                        staleAge = c.ageIsStale,
                    )
                }
            }
        }
    }
}

private val HEADERS = listOf("Source", "High", "Low", "Rain", "Peak", "Night", "Amount", "Cloud", "Bias", "Age")

@Composable
private fun TableRow(
    tag: String,
    values: List<String>,
    showAmount: Boolean,
    color: Color,
    bold: Boolean,
    staleAge: Boolean,
    onCellClick: ((Int) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().testTag(tag)) {
        values.forEachIndexed { i, v ->
            if (i == DaySourceComparison.AMOUNT_COLUMN && !showAmount) return@forEachIndexed
            val isAge = i == values.lastIndex
            Text(
                v,
                modifier = Modifier.weight(DaySourceComparison.COLUMN_WEIGHTS[i])
                    .then(if (onCellClick != null) Modifier.clickable { onCellClick(i) }.testTag("${tag}_$i") else Modifier),
                color = if (isAge && staleAge) ObsStyle.textSecondary else color,
                fontSize = 12.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                maxLines = 1,
            )
        }
    }
}
