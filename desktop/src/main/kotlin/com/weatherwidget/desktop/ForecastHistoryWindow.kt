package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.AxisScale
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.ErrorSample
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.EvolutionPoint
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.ForecastSample
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.TimeAxis
import com.weatherwidget.shared.graph.ForecastEvolutionStyle
import com.weatherwidget.shared.graph.ForecastHistoryViewLogic
import com.weatherwidget.shared.graph.ForecastHistoryViewLogic.GraphMode
import com.weatherwidget.shared.graph.NiceAxisScale
import com.weatherwidget.stats.desktop.DesktopAccuracyCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

private const val MS_IN_A_DAY = 86_400_000L
private val METEO_LIKE_IDS = setOf(
    WeatherSource.VISUAL_CROSSING.id,
    WeatherSource.OPEN_METEO.id,
    WeatherSource.WEATHER_API.id,
)

/** Everything the window needs for one (targetDate, source) view, computed off the UI thread. */
private data class HistoryData(
    val nwsPoints: List<EvolutionPoint>,
    val meteoPoints: List<EvolutionPoint>,
    val apiHigh: Float?,
    val apiLow: Float?,
    val appHigh: Float?,
    val appLow: Float?,
    val isPast: Boolean,
    val snapshotCount: Int,
    val newestFetchAgeMs: Long?,
    val accuracySummary: String,
)

/**
 * Desktop port of Android's `ForecastHistoryActivity`: shows how the forecast for one target day
 * evolved across successive fetches (Evolution mode) and the error vs actuals (Error mode), with
 * day navigation, source cycling, an accuracy summary, and a freshness line. Graph geometry is the
 * shared [ForecastEvolutionGeometry]; drawing is Compose `DrawScope` (Android draws the same
 * geometry with `Canvas`/`Bitmap`).
 */
@Composable
internal fun ForecastHistoryWindow(
    weatherDao: DesktopWeatherDao,
    config: DesktopConfig,
    onClose: () -> Unit,
    onConfigUpdate: (DesktopConfig) -> Unit = {},
) {
    val state = rememberWindowState(
        position = if (config.historyWindowX != null && config.historyWindowY != null) {
            WindowPosition(config.historyWindowX.dp, config.historyWindowY.dp)
        } else {
            WindowPosition(Alignment.Center)
        },
        width = config.historyWindowWidth?.dp ?: 520.dp,
        height = config.historyWindowHeight?.dp ?: 720.dp,
    )

    // Persist size/position back to config so the window reopens where the user left it.
    LaunchedEffect(state.position, state.size) {
        val position = state.position
        val size = state.size
        if (position is WindowPosition.Absolute) {
            onConfigUpdate(
                config.copy(
                    historyWindowX = position.x.value,
                    historyWindowY = position.y.value,
                    historyWindowWidth = size.width.value,
                    historyWindowHeight = size.height.value,
                )
            )
        }
    }

    Window(
        onCloseRequest = onClose,
        state = state,
        title = "Forecast History",
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        }
    ) {
        val visibleSources = remember(config.visibleSources) {
            config.visibleSources.map { WeatherSource.fromId(it) }.ifEmpty { listOf(WeatherSource.NWS) }
        }
        var targetDate by remember { mutableStateOf(LocalDate.now()) }
        var source by remember {
            mutableStateOf(
                WeatherSource.fromId(config.weatherSource).takeIf { it in visibleSources }
                    ?: visibleSources.first(),
            )
        }
        var graphMode by remember { mutableStateOf(GraphMode.EVOLUTION) }
        var loading by remember { mutableStateOf(true) }
        var data by remember { mutableStateOf<HistoryData?>(null) }

        LaunchedEffect(targetDate, source, config.lat, config.lon) {
            loading = true
            data = withContext(Dispatchers.IO) {
                loadHistory(weatherDao, config, targetDate, source, visibleSources)
            }
            loading = false
        }

        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                Header(
                    targetDate = targetDate,
                    source = source,
                    graphMode = graphMode,
                    canGoBack = targetDate.isAfter(LocalDate.now().minusDays(ForecastHistoryViewLogic.MAX_HISTORY_DAYS_BACK)),
                    canGoForward = targetDate.isBefore(LocalDate.now().plusDays(7)),
                    onPrev = { targetDate = targetDate.minusDays(1) },
                    onNext = { targetDate = targetDate.plusDays(1) },
                    onCycleSource = {
                        val idx = visibleSources.indexOf(source)
                        source = visibleSources[(idx + 1) % visibleSources.size]
                    },
                    onToggleMode = {
                        graphMode = if (graphMode == GraphMode.EVOLUTION) GraphMode.ERROR else GraphMode.EVOLUTION
                    },
                )
                Spacer(Modifier.height(8.dp))

                val d = data
                when {
                    loading -> Text("Loading…", color = Color.Gray)
                    d == null -> Text("No data.", color = Color.Gray)
                    else -> Content(d, graphMode, source)
                }
            }
        }
    }
}

@Composable
private fun Header(
    targetDate: LocalDate,
    source: WeatherSource,
    graphMode: GraphMode,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCycleSource: () -> Unit,
    onToggleMode: () -> Unit,
) {
    val dateText = "${targetDate.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())}, " +
        "${targetDate.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())} ${targetDate.dayOfMonth}"
    Column {
        Text("Forecast History", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrev, enabled = canGoBack) { Text("◀") }
            Text(dateText, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = onNext, enabled = canGoForward) { Text("▶") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCycleSource) { Text(source.shortDisplayName) }
            OutlinedButton(onClick = onToggleMode) {
                Text(if (graphMode == GraphMode.EVOLUTION) "Evolution" else "Error")
            }
        }
    }
}

@Composable
private fun Content(d: HistoryData, graphMode: GraphMode, source: WeatherSource) {
    val textMeasurer = rememberTextMeasurer()
    val isError = graphMode == GraphMode.ERROR
    val hasPoints = d.nwsPoints.isNotEmpty() || d.meteoPoints.isNotEmpty()

    if (!hasPoints) {
        Text("No forecast snapshots for ${source.displayName} on this day.", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
    } else if (isError && (d.apiHigh == null || d.apiLow == null) && (d.appHigh == null || d.appLow == null)) {
        Text("Error view needs actuals — pick a past day.", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
    } else {
        GraphCard(if (isError) "High forecast error" else "High forecast evolution") {
            EvolutionGraph(d.nwsPoints, d.meteoPoints, d.apiHigh, d.appHigh, isHigh = true, isError = isError, textMeasurer = textMeasurer)
        }
        Spacer(Modifier.height(12.dp))
        GraphCard(if (isError) "Low forecast error" else "Low forecast evolution") {
            EvolutionGraph(d.nwsPoints, d.meteoPoints, d.apiLow, d.appLow, isHigh = false, isError = isError, textMeasurer = textMeasurer)
        }
        Spacer(Modifier.height(12.dp))
    }

    Legend(source, d.isPast)
    Spacer(Modifier.height(8.dp))

    if (d.isPast && ((d.apiHigh != null && d.apiLow != null) || (d.appHigh != null && d.appLow != null))) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (d.apiHigh != null && d.apiLow != null) {
                    Text("${source.displayName} actual: ${fmt(d.apiHigh)} / ${fmt(d.apiLow)}",
                        color = parseColor(ForecastEvolutionStyle.API_ACTUAL_COLOR), fontSize = 13.sp)
                }
                if (d.appHigh != null && d.appLow != null) {
                    Text("Location actual: ${fmt(d.appHigh)} / ${fmt(d.appLow)}",
                        color = parseColor(ForecastEvolutionStyle.APP_ACTUAL_COLOR), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // Freshness line.
    val freshness = buildString {
        append("${d.snapshotCount} snapshot${if (d.snapshotCount == 1) "" else "s"}")
        d.newestFetchAgeMs?.let { append(" · newest ${formatAge(it)} ago") }
    }
    Text(freshness, color = Color.Gray, fontSize = 11.sp)
    Spacer(Modifier.height(12.dp))

    Text("Accuracy (last 30 days)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
    Text(d.accuracySummary, color = Color.Gray, fontSize = 12.sp)
}

@Composable
private fun GraphCard(title: String, content: @Composable () -> Unit) {
    // Match Android's bg_graph_card fill (#222226) so the shared #333 gridlines read as the same
    // subtle "half-faded" grid (the grid is one step lighter than the card, not lighter-on-lighter).
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF222226))) {
        Column(Modifier.padding(8.dp)) {
            Text(title, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun Legend(source: WeatherSource, isPast: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val seriesColor = if (source == WeatherSource.NWS) ForecastEvolutionStyle.NWS_COLOR else ForecastEvolutionStyle.METEO_COLOR
        LegendDot(parseColor(seriesColor), source.shortDisplayName)
        if (isPast) {
            LegendDot(parseColor(ForecastEvolutionStyle.API_ACTUAL_COLOR), "API actual")
            LegendDot(parseColor(ForecastEvolutionStyle.APP_ACTUAL_COLOR), "Location actual")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun EvolutionGraph(
    nwsPoints: List<EvolutionPoint>,
    meteoPoints: List<EvolutionPoint>,
    actual: Float?,
    appActual: Float?,
    isHigh: Boolean,
    isError: Boolean,
    textMeasurer: TextMeasurer,
) {
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        if (isError) drawError(nwsPoints, meteoPoints, actual, appActual, isHigh, textMeasurer)
        else drawEvolution(nwsPoints, meteoPoints, actual, appActual, isHigh, textMeasurer)
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing (Compose DrawScope mirror of ForecastEvolutionRenderer)
// ---------------------------------------------------------------------------------------------

private class Layout(val left: Float, val right: Float, val top: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun DrawScope.layout(): Layout = Layout(
    left = ForecastEvolutionStyle.PADDING_LEFT_DP.dp.toPx(),
    right = size.width - ForecastEvolutionStyle.PADDING_RIGHT_DP.dp.toPx(),
    top = ForecastEvolutionStyle.PADDING_TOP_DP.dp.toPx(),
    bottom = size.height - ForecastEvolutionStyle.PADDING_BOTTOM_DP.dp.toPx(),
)

private fun DrawScope.drawEvolution(
    nwsPoints: List<EvolutionPoint>,
    meteoPoints: List<EvolutionPoint>,
    actual: Float?,
    appActual: Float?,
    isHigh: Boolean,
    tm: TextMeasurer,
) {
    val tempFor = { p: EvolutionPoint -> if (isHigh) p.highTemp else p.lowTemp }
    val nws = ForecastEvolutionGeometry.bucketize(nwsPoints, tempFor)
    val meteo = ForecastEvolutionGeometry.bucketize(meteoPoints, tempFor)
    if (nws.isEmpty() && meteo.isEmpty()) return
    val allTemps = ForecastEvolutionGeometry.collectTemps(nws + meteo, tempFor, actual, appActual)
    if (allTemps.isEmpty()) return

    val axis = NiceAxisScale.compute(allTemps.minOrNull() ?: 0f, allTemps.maxOrNull() ?: 100f)
    val l = layout()
    val timeAxis = TimeAxis((nws + meteo).map { it.fetchedAt }, ForecastEvolutionGeometry.tickDivisionsForWidth(l.width, spacingPx = 46f, maxDivisions = 20))

    drawGridAndAxes(l, axis, timeAxis, tm, isError = false)
    drawSeriesCurve(nws.mapNotNull { p -> tempFor(p)?.let { it to p.fetchedAt } }, axis, timeAxis, l, parseColor(ForecastEvolutionStyle.NWS_COLOR))
    drawSeriesCurve(meteo.mapNotNull { p -> tempFor(p)?.let { it to p.fetchedAt } }, axis, timeAxis, l, parseColor(ForecastEvolutionStyle.METEO_COLOR))
    drawActualLine(l, axis, actual, parseColor(ForecastEvolutionStyle.API_ACTUAL_COLOR), dashed = true,
        "API actual: ${actual?.let { fmt(it) } ?: ""}", tm)
    drawActualLine(l, axis, appActual, parseColor(ForecastEvolutionStyle.APP_ACTUAL_COLOR), dashed = false,
        "Location actual: ${appActual?.let { fmt(it) } ?: ""}", tm)
}

private fun DrawScope.drawError(
    nwsPoints: List<EvolutionPoint>,
    meteoPoints: List<EvolutionPoint>,
    actual: Float?,
    appActual: Float?,
    isHigh: Boolean,
    tm: TextMeasurer,
) {
    val baseline = appActual ?: actual ?: return
    val tempFor = { p: EvolutionPoint -> if (isHigh) p.highTemp else p.lowTemp }
    val nws = ForecastEvolutionGeometry.bucketize(nwsPoints, tempFor)
    val meteo = ForecastEvolutionGeometry.bucketize(meteoPoints, tempFor)
    val all = nws + meteo
    if (all.isEmpty()) return
    val errors = ForecastEvolutionGeometry.errorSamples(all, tempFor, baseline)
    if (errors.isEmpty()) return

    val yBound = maxOf(3f, ceil(errors.maxOf { abs(it.error) }) + 1f)
    val axis = NiceAxisScale.computeSymmetric(yBound, minRange = 6f)
    val l = layout()
    val timeAxis = TimeAxis(errors.map { it.fetchedAt }, ForecastEvolutionGeometry.tickDivisionsForWidth(l.width, spacingPx = 46f, maxDivisions = 20))

    drawGridAndAxes(l, axis, timeAxis, tm, isError = true)

    val zeroY = axis.valueToY(0f, l.top, l.height)
    drawLine(parseColor(ForecastEvolutionStyle.APP_ACTUAL_COLOR), Offset(l.left, zeroY), Offset(l.right, zeroY),
        strokeWidth = ForecastEvolutionStyle.ZERO_LINE_STROKE_DP.dp.toPx(), pathEffect = dash())
    label(tm, "Location actual", l.right + gap(), zeroY, parseColor(ForecastEvolutionStyle.APP_ACTUAL_COLOR), leftAligned = true)

    if (actual != null && appActual != null) {
        val apiBias = actual - appActual
        if (abs(apiBias) > 0.01f) {
            val apiY = axis.valueToY(apiBias, l.top, l.height)
            drawLine(parseColor(ForecastEvolutionStyle.API_ACTUAL_COLOR), Offset(l.left, apiY), Offset(l.right, apiY),
                strokeWidth = ForecastEvolutionStyle.API_ACTUAL_STROKE_DP.dp.toPx(), pathEffect = dash())
            label(tm, "API actual", l.right + gap(), apiY, parseColor(ForecastEvolutionStyle.API_ACTUAL_COLOR), leftAligned = true)
        }
    }

    drawErrorCurve(errors.filter { it.source == WeatherSource.NWS }, axis, timeAxis, l, parseColor(ForecastEvolutionStyle.NWS_COLOR))
    drawErrorCurve(errors.filter { it.source.id in METEO_LIKE_IDS }, axis, timeAxis, l, parseColor(ForecastEvolutionStyle.METEO_COLOR))
}

private fun DrawScope.drawGridAndAxes(l: Layout, axis: AxisScale, timeAxis: TimeAxis, tm: TextMeasurer, isError: Boolean) {
    val grid = parseColor(ForecastEvolutionStyle.GRID_COLOR)
    val labelColor = parseColor(ForecastEvolutionStyle.LABEL_COLOR)
    for (tick in axis.ticks) {
        val y = axis.valueToY(tick, l.top, l.height)
        drawLine(grid, Offset(l.left, y), Offset(l.right, y), strokeWidth = ForecastEvolutionStyle.GRID_STROKE_DP.dp.toPx())
        val text = if (isError) ForecastEvolutionGeometry.formatErrorLabel(tick) else ForecastEvolutionGeometry.formatAxisLabel(tick)
        label(tm, text, l.left - gap(), y, labelColor, leftAligned = false)
    }
    val labelBaseline = l.bottom + (ForecastEvolutionStyle.LABEL_GAP_DP + 8f).dp.toPx()
    for (tick in timeAxis.ticks) {
        val x = timeAxis.xForTime(tick, l.left, l.width)
        drawLine(grid, Offset(x, l.top), Offset(x, l.bottom), strokeWidth = ForecastEvolutionStyle.GRID_STROKE_DP.dp.toPx())
        labelSlanted(tm, timeAxis.formatLabel(tick), x, labelBaseline, labelColor)
    }
}

private fun DrawScope.drawSeriesCurve(points: List<Pair<Float, Long>>, axis: AxisScale, timeAxis: TimeAxis, l: Layout, color: Color) {
    if (points.isEmpty()) return
    val sorted = points.sortedBy { it.second }
    val coords = sorted.map { (temp, t) -> Offset(timeAxis.xForTime(t, l.left, l.width), axis.valueToY(temp, l.top, l.height)) }
    drawSmoothPath(coords, color, ForecastEvolutionStyle.CURVE_STROKE_DP.dp.toPx())
    val r = ForecastEvolutionStyle.DATA_POINT_RADIUS_DP.dp.toPx()
    coords.forEach { drawCircle(color, radius = r, center = it) }
}

private fun DrawScope.drawErrorCurve(series: List<ErrorSample>, axis: AxisScale, timeAxis: TimeAxis, l: Layout, color: Color) {
    if (series.isEmpty()) return
    val sorted = series.sortedBy { it.fetchedAt }
    val coords = sorted.map { Offset(timeAxis.xForTime(it.fetchedAt, l.left, l.width), axis.valueToY(it.error, l.top, l.height)) }
    drawSmoothPath(coords, color, ForecastEvolutionStyle.CURVE_STROKE_DP.dp.toPx())
    val r = ForecastEvolutionStyle.DATA_POINT_RADIUS_DP.dp.toPx()
    coords.forEach { drawCircle(color, radius = r, center = it) }
}

/** Quadratic-bezier smoothing matching the Android renderer (control point at the midpoint X). */
private fun DrawScope.drawSmoothPath(coords: List<Offset>, color: Color, stroke: Float) {
    if (coords.isEmpty()) return
    val path = Path()
    var last = coords.first()
    path.moveTo(last.x, last.y)
    for (i in 1 until coords.size) {
        val p = coords[i]
        val controlX = (last.x + p.x) / 2f
        path.quadraticBezierTo(controlX, last.y, p.x, p.y)
        last = p
    }
    drawPath(path, color, style = Stroke(width = stroke))
}

private fun DrawScope.drawActualLine(l: Layout, axis: AxisScale, value: Float?, color: Color, dashed: Boolean, text: String, tm: TextMeasurer) {
    if (value == null) return
    val y = axis.valueToY(value, l.top, l.height)
    val stroke = if (dashed) ForecastEvolutionStyle.API_ACTUAL_STROKE_DP.dp.toPx() else ForecastEvolutionStyle.APP_ACTUAL_STROKE_DP.dp.toPx()
    drawLine(color, Offset(l.left, y), Offset(l.right, y), strokeWidth = stroke, pathEffect = if (dashed) dash() else null)
    label(tm, text, l.right + gap(), y, color, leftAligned = true)
}

private fun DrawScope.dash() = PathEffect.dashPathEffect(
    floatArrayOf(ForecastEvolutionStyle.DASH_ON_DP.dp.toPx(), ForecastEvolutionStyle.DASH_OFF_DP.dp.toPx()), 0f,
)

private fun DrawScope.gap() = ForecastEvolutionStyle.LABEL_GAP_DP.dp.toPx()

/** Draws a label vertically centered on [yCenter]; left- or right-anchored at [x]. */
private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, yCenter: Float, color: Color, leftAligned: Boolean) {
    if (text.isBlank()) return
    val layout = tm.measure(text, TextStyle(color = color, fontSize = 11.sp))
    val topLeftX = if (leftAligned) x else x - layout.size.width
    drawText(layout, topLeft = Offset(topLeftX, yCenter - layout.size.height / 2f))
}

/** Draws a slanted x-axis time label, right-anchored at the tick [x] with its baseline at [baseline]. */
private fun DrawScope.labelSlanted(tm: TextMeasurer, text: String, x: Float, baseline: Float, color: Color) {
    if (text.isBlank()) return
    val layout = tm.measure(text, TextStyle(color = color, fontSize = 11.sp))
    rotate(ForecastEvolutionStyle.X_LABEL_SLANT_DEG, pivot = Offset(x, baseline)) {
        drawText(layout, topLeft = Offset(x - layout.size.width, baseline - layout.size.height))
    }
}

// ---------------------------------------------------------------------------------------------
// Data loading
// ---------------------------------------------------------------------------------------------

private fun loadHistory(
    dao: DesktopWeatherDao,
    config: DesktopConfig,
    targetDate: LocalDate,
    source: WeatherSource,
    visibleSources: List<WeatherSource>,
): HistoryData {
    val lat = config.lat
    val lon = config.lon
    val targetEpoch = targetDate.toEpochDay() * MS_IN_A_DAY
    val isPast = targetDate.isBefore(LocalDate.now())

    val rows = dao.getForecastEvolution(targetEpoch, lat, lon).filter { it.source == source.id }
    val points = rows.map { row ->
        val forecastDate = LocalDate.ofEpochDay(row.forecastDate / MS_IN_A_DAY)
        EvolutionPoint(
            forecastDate = forecastDate.toString(),
            fetchedAt = row.fetchedAt,
            daysAhead = java.time.temporal.ChronoUnit.DAYS.between(forecastDate, targetDate).toInt(),
            highTemp = row.highTemp,
            lowTemp = row.lowTemp,
            source = WeatherSource.fromId(row.source),
        )
    }
    val nwsPoints = points.filter { it.source == WeatherSource.NWS }
    val meteoPoints = points.filter { it.source.id in METEO_LIKE_IDS }

    // Actuals (past days only).
    var apiHigh: Float? = null
    var apiLow: Float? = null
    var appHigh: Float? = null
    var appLow: Float? = null
    if (isPast) {
        val apiActual = dao.getForecastsInRangeBySource(targetEpoch, targetEpoch, lat, lon, source.id)
            .filter { it.highTemp != null && it.lowTemp != null }
            .maxByOrNull { it.fetchedAt }
        apiHigh = apiActual?.highTemp
        apiLow = apiActual?.lowTemp
        val appActual = dao.getExtremesInRange(targetEpoch, targetEpoch, lat, lon).firstOrNull { it.source == source.id }
        appHigh = appActual?.highTemp
        appLow = appActual?.lowTemp
    }

    val newestFetch = rows.maxByOrNull { it.fetchedAt }?.fetchedAt
    val newestAge = newestFetch?.let { System.currentTimeMillis() - it }

    val calc = DesktopAccuracyCalculator(dao)
    val summary = buildString {
        visibleSources.forEachIndexed { index, s ->
            val stats = calc.calculateAccuracy(s.id, lat, lon, days = 30)
            if (stats != null && stats.totalForecasts > 0) {
                append("${s.displayName}\n")
                append("High ±%.1f°%s  Low ±%.1f°%s\n".format(
                    stats.avgHighError, ForecastHistoryViewLogic.formatBias(stats.highBias),
                    stats.avgLowError, ForecastHistoryViewLogic.formatBias(stats.lowBias),
                ))
                append("%% within 3°: %.0f%%  ·  %d days".format(stats.percentWithin3Degrees, stats.totalForecasts))
            } else {
                append("${s.displayName}: No data yet")
            }
            if (index < visibleSources.size - 1) append("\n\n")
        }
    }.ifBlank { "No accuracy history yet — comparisons build up over the following weeks." }

    return HistoryData(
        nwsPoints = nwsPoints,
        meteoPoints = meteoPoints,
        apiHigh = apiHigh, apiLow = apiLow,
        appHigh = appHigh, appLow = appLow,
        isPast = isPast,
        snapshotCount = rows.size,
        newestFetchAgeMs = newestAge,
        accuracySummary = summary,
    )
}

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------

private fun fmt(v: Float): String = ForecastEvolutionGeometry.formatTempLabel(v)

private fun formatAge(durationMs: Long): String {
    val minutes = durationMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
    }
}

/** Parses a `#RRGGBB` / `#AARRGGBB` style string into a Compose [Color]. */
private fun parseColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val argb = when (clean.length) {
        6 -> 0xFF000000L or clean.toLong(16)
        8 -> clean.toLong(16)
        else -> 0xFFFFFFFFL
    }
    return Color(argb.toInt())
}
