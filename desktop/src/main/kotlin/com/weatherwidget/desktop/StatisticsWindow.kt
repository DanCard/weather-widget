package com.weatherwidget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.stats.desktop.DesktopAccuracyCalculator
import com.weatherwidget.stats.desktop.DesktopAccuracyStatistics
import com.weatherwidget.stats.desktop.DesktopDailyAccuracy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
internal fun StatisticsWindow(
    weatherDao: DesktopWeatherDao,
    config: DesktopConfig,
    onClose: () -> Unit,
) {
    val state = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        width = 460.dp,
        height = 620.dp,
    )
    Window(onCloseRequest = onClose, state = state, title = "Forecast Accuracy") {
        val source = config.weatherSource
        var loading by remember { mutableStateOf(true) }
        var stats by remember { mutableStateOf<DesktopAccuracyStatistics?>(null) }
        var breakdown by remember { mutableStateOf<List<DesktopDailyAccuracy>>(emptyList()) }

        LaunchedEffect(source, config.lat, config.lon) {
            loading = true
            val calc = DesktopAccuracyCalculator(weatherDao)
            val (s, b) = withContext(Dispatchers.IO) {
                calc.calculateAccuracy(source, config.lat, config.lon, days = 30) to
                    calc.getDailyAccuracyBreakdown(source, config.lat, config.lon, days = 30)
            }
            stats = s
            breakdown = b
            loading = false
        }

        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Forecast Accuracy", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("$source · ${config.label} · last 30 days", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))

                when {
                    loading -> Text("Calculating…", color = Color.Gray)
                    stats == null -> EmptyState()
                    else -> {
                        SummaryCard(stats!!)
                        Spacer(Modifier.height(16.dp))
                        Text("Day-by-day", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        BreakdownTable(breakdown)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column {
        Text("Not enough history yet.", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Accuracy compares each day's actual high/low against the forecast made the day before. " +
                "It needs at least one full day of continuous operation before the first comparison appears, " +
                "and builds up over the following weeks.",
            fontSize = 13.sp,
            color = Color.Gray,
        )
    }
}

@Composable
private fun SummaryCard(s: DesktopAccuracyStatistics) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Score ", fontSize = 16.sp)
                Text("%.1f / 5".format(s.accuracyScore), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = scoreColor(s.accuracyScore))
            }
            Spacer(Modifier.height(8.dp))
            StatRow("Avg error (high / low)", "%.1f° / %.1f°".format(s.avgHighError, s.avgLowError))
            StatRow("Bias (high / low)", "${biasText(s.highBias)} / ${biasText(s.lowBias)}")
            StatRow("Max error", "${s.maxError}°")
            StatRow("Within ±3°", "%.0f%%".format(s.percentWithin3Degrees))
            StatRow("Days compared", "${s.totalForecasts}")
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BreakdownTable(rows: List<DesktopDailyAccuracy>) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            HeaderCell("Date", 2f); HeaderCell("Fcst H/L", 1.4f); HeaderCell("Actual H/L", 1.4f); HeaderCell("Err H/L", 1.2f)
        }
        rows.reversed().forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                BodyCell(r.date, 2f)
                BodyCell("${r.forecastHigh}/${r.forecastLow}", 1.4f)
                BodyCell("${r.actualHigh}/${r.actualLow}", 1.4f)
                Row(Modifier.weight(1.2f)) {
                    Text(signed(r.highError), fontSize = 12.sp, color = errColor(r.highError))
                    Text("/", fontSize = 12.sp, color = Color.Gray)
                    Text(signed(r.lowError), fontSize = 12.sp, color = errColor(r.lowError))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(text, Modifier.weight(weight), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BodyCell(text: String, weight: Float) {
    Text(text, Modifier.weight(weight), fontSize = 12.sp)
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

private fun biasText(bias: Double): String = when {
    abs(bias) < 0.05 -> "0.0°"
    bias > 0 -> "%.1f° low".format(bias)   // actual warmer than forecast → forecast ran low
    else -> "%.1f° high".format(-bias)
}

private fun scoreColor(score: Double): Color = when {
    score >= 4.5 -> Color(0xFF4CAF50)
    score >= 3.5 -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

private fun errColor(err: Int): Color = when {
    abs(err) <= 2 -> Color(0xFF4CAF50)
    abs(err) <= 5 -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}
