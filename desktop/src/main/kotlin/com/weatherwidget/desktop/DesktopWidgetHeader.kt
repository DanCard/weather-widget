package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.PreferredSourceHome
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
internal fun WidgetHeader(
    config: DesktopConfig,
    forecast: ForecastSnapshot,
    resolvedCurrentTemp: Float? = null,
    resolvedDeltaFromYesterday: Float? = null,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the Observations window for the viewed day (a past day lets its refresh refill stale Google hours). */
    onOpenObservations: (LocalDate) -> Unit,
    onOpenHistory: (viewedDate: LocalDate) -> Unit = {},
    onUpdateLocation: () -> Unit,
    headerTime: LocalDateTime = LocalDateTime.now(),
    scale: Float = 1f,
    /** Whether today is among the days the daily view is rendering; drives the history target date. */
    todayInView: Boolean = true,
    /** Whether today or yesterday is on screen; drives the current-observations button. */
    observationsInView: Boolean = true,
    /** The daily window the home button's recentre goes to, zoom kept (`DesktopDailyViewState.homeDateOffset`). */
    homeDateOffset: Int = 0,
) {
    val showWeatherSummary = config.viewMode.isHourly
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    // Measures the daily header's pieces for the shared fit (HeaderFit).
    val headerTextMeasurer = rememberTextMeasurer()
    val targetHour = remember(headerTime) { headerTime.truncatedTo(ChronoUnit.HOURS) }

    val nowEpoch = System.currentTimeMillis()
    val zoneId = remember { ZoneId.systemDefault() }
    val nowLocal = remember(nowEpoch, zoneId) {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(nowEpoch), zoneId)
    }
    val displaySource = remember(config.displaySource) {
        WeatherSource.fromDisplaySource(config.displaySource)
    }
    val displayTemp = resolvedCurrentTemp ?: forecast.resolved.currentTemp
    val deltaVal = resolvedDeltaFromYesterday ?: forecast.resolved.deltaFromYesterday
    val isHourly = config.viewMode.isHourly
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val headerWidthDp = with(density) { windowInfo.containerSize.width.toDp().value.toInt() }
    // Icon, temp, delta, rain % and source label: the shared header rule Android's headers use
    // (plans/261021-header-shared-content-audit.md).
    val content = remember(forecast.raw, displaySource, displayTemp, deltaVal, nowLocal, targetHour, config.viewMode, config.lat, config.lon, config.settings.useCelsius, headerWidthDp) {
        desktopHeaderContent(
            forecast = forecast,
            displaySourceId = displaySource.id,
            viewMode = config.viewMode,
            currentTemp = displayTemp,
            deltaFromYesterday = deltaVal,
            now = nowLocal,
            centerTime = targetHour,
            lat = config.lat,
            lon = config.lon,
            useCelsius = config.settings.useCelsius,
            widthDp = headerWidthDp,
        )
    }

    val toggleWeatherSource = { cycleDisplaySource(config, onUpdateConfig) }

    // Daily header inputs, decided before layout so the shared fit can weigh every item.
    val iconSizeDp = 15 * scale
    val spacingDp = 14 * scale
    // The home button never changes the view (the daily view is already home in that sense). It
    // resets the SOURCE when another one is shown, else — on the preferred source with today panned
    // off screen — brings back yesterday + today.
    val homeAction = PreferredSourceHome.homeAction(
        currentSourceId = config.displaySource,
        visibleSourceIds = config.effectiveSources,
        todayInView = todayInView,
    )
    // Months view (zoom out), as on Android's daily header; hidden while it is open (the home
    // button brings the daily view back) or disabled in settings.
    val wantMonthsButton = !config.monthsView && config.settings.showDailyZoomOutButton
    val dateText = DesktopHeaderDate.dailyDate(nowLocal).format(dateFormatter)
    val tempStyle = MaterialTheme.typography.displaySmall.copy(fontSize = (HEADER_CURRENT_TEMP_SP * scale).sp)
    val deltaStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (9 * scale).sp)
    val captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (7.5f * scale).sp)
    val precipStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = (HEADER_TEMP_BASE_SP * (content.precipFontScale ?: 1f) * scale).sp,
    )
    val sourceStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (10 * scale).sp)
    val dateStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (12 * scale).sp)
    // The shared drop order (`HeaderFit`, plans/261022-shared-header-fit-priority.md), daily and
    // hourly alike: the hourly row is the graph selector and three icons, no date, no home/months.
    val hourlySpacingDp = 12 * scale
    // Cycling graph selector: the emoji names the view it switches to.
    val (graphSelectorEmoji, graphSelectorNextView) = when (config.viewMode) {
        ViewMode.CLOUD_COVER -> "🌧️" to ViewMode.PRECIPITATION
        ViewMode.PRECIPITATION -> "🌡️" to ViewMode.TEMPERATURE
        else -> "☁️" to ViewMode.CLOUD_COVER
    }
    val graphSelectorStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (13 * scale).sp)
    val headerMeasured = remember(content, scale, isHourly, graphSelectorEmoji, homeAction, wantMonthsButton, observationsInView, dateText, headerTextMeasurer, density) {
        fun widthDp(text: String, style: androidx.compose.ui.text.TextStyle) =
            with(density) { headerTextMeasurer.measure(text, style).size.width.toDp().value }
        HeaderFit.Measured(
            width = 0f, // the BoxWithConstraints below supplies it
            rightCluster = widthDp(content.sourceLabel, sourceStyle) + SOURCE_LABEL_END_PADDING_DP + 14 * scale,
            gap = spacingDp,
            seamOffset = HeaderFit.SEAM_OFFSET_DP * scale,
            temp = widthDp(content.tempText ?: "—", tempStyle),
            rain = content.precipText?.takeIf { content.precipFontScale != null }
                ?.let { PRECIP_MARGIN_DP + widthDp(it, precipStyle) } ?: 0f,
            delta = content.deltaText?.let { DELTA_MARGIN_DP + widthDp(it, deltaStyle) } ?: 0f,
            icon = if (content.iconName != null) 18 * scale else 0f,
            date = if (isHourly) 0f else widthDp(dateText, dateStyle),
            caption = if (content.deltaText != null) CAPTION_MARGIN_DP + widthDp(DELTA_CAPTION, captionStyle) else 0f,
            baseButtons = if (isHourly) 4 else (if (observationsInView) 1 else 0) + 1,
            wantHome = !isHourly && homeAction != null,
            wantMonths = !isHourly && wantMonthsButton,
            // The hourly row's emoji is not icon-sized: the average keeps the row's true width, so it
            // ends on the line.
            centreButton = if (isHourly) (widthDp(graphSelectorEmoji, graphSelectorStyle) + 3 * iconSizeDp) / 4 else iconSizeDp,
            centreButtonSpacing = if (isHourly) hourlySpacingDp else spacingDp,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { toggleWeatherSource() }
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measured = headerMeasured.copy(width = maxWidth.value)
            val fit = remember(measured) {
                HeaderFit.resolve(measured).also { HeaderFitLog.changed(if (isHourly) "hourly" else "daily", it) }
            }
            // The buttons end on HeaderFit's line right of the middle and the daily date starts just
            // after it, so neither the temperature's width nor home appearing on a source switch
            // moves the date — and history stays put when switching daily ↔ hourly.
            val buttonsSpan = HeaderFit.buttonsSpan(measured, fit)
            val dateLeft = HeaderFit.dateLeft(measured, fit)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left cluster: current temp/icon clickable to toggle view.
                //
                // Deliberately NOT weighted. The three clusters used to take exactly a third each, so a
                // tall narrow window — where the height-derived `scale` grows the fonts but the third
                // does not grow with them — starved this cluster: the rain chance, its last child, was
                // squeezed to a one-glyph column and stacked as "1/5/%". Sizing to content instead lets
                // temp + delta + "from yest" + rain chance always render in full, and pushes the
                // shortage onto the centre cluster's slack (see below) rather than onto the text.
                Row(
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            val targetMode = if (isHourly) ViewMode.DAILY else ViewMode.TEMPERATURE
                            onUpdateConfig(config.copy(viewMode = targetMode))
                        }.testTag("current_temp_toggle")
                    ) {
                        // No icon when an hourly view's source has no row for this hour (HeaderContent).
                        content.iconName?.takeIf { fit.showIcon }?.let { iconName ->
                            androidx.compose.foundation.Image(
                                painter = WeatherIcon.painterForIconName(iconName),
                                contentDescription = null,
                                modifier = Modifier.size((18 * scale).dp).padding(end = 4.dp).testTag("header_weather_icon")
                            )
                        }
                        if (fit.showTemp) {
                            Text(text = content.tempText ?: "—", style = tempStyle)
                        }
                    }
                    content.deltaText?.takeIf { fit.showDelta }?.let { deltaText ->
                        Spacer(Modifier.width(DELTA_MARGIN_DP.dp))
                        Text(
                            text = deltaText,
                            style = deltaStyle,
                            color = Color(0xFFFF6B35),
                            modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                        )
                        if (fit.showCaption) {
                            Spacer(Modifier.width(CAPTION_MARGIN_DP.dp))
                            // Caption clarifying the delta's meaning; smaller/dimmer than the delta
                            // itself. Single-line: two stacked half-words explain nothing, and the fit
                            // only shows it where it has room to render in full.
                            Text(
                                text = DELTA_CAPTION,
                                style = captionStyle,
                                color = Color(0xFFFF6B35).copy(alpha = 0.7f),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                            )
                        }
                    }
                    val precipText = content.precipText
                    if (precipText != null && content.precipFontScale != null && fit.showRain) {
                        Spacer(Modifier.width(PRECIP_MARGIN_DP.dp))
                        Text(
                            text = precipText,
                            style = precipStyle,
                            color = Color(0xFF4FC3F7),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .offset(y = 2.dp)
                                .clickable {
                                    onUpdateConfig(config.copy(viewMode = ViewMode.PRECIPITATION))
                                }
                        )
                    }
                }

                // The middle: room the two content-sized clusters leave. The buttons (and the daily
                // date) are overlays at HeaderFit's positions, below.
                Spacer(Modifier.weight(1f))

                // Right cluster: API source + Settings gear. Content-sized, like the left cluster —
                // it is pinned to the right edge by the spacer's weight, not by a width share.
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val visibleSources = config.effectiveSources
                    // Show the shared short label (e.g. "Meteo"), matching Android's API indicator, rather
                    // than the raw stored id ("OPEN_METEO"). One source of truth: WeatherSource.shortDisplayName.
                    val sourceLabel = content.sourceLabel
                    if (visibleSources.size > 1) {
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = (10 * scale).sp,
                            modifier = Modifier.clickable {
                                cycleDisplaySource(config, onUpdateConfig)
                            }.padding(end = SOURCE_LABEL_END_PADDING_DP.dp)
                        )
                    } else {
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = (10 * scale).sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = SOURCE_LABEL_END_PADDING_DP.dp)
                        )
                    }
                    Icon(
                        painter = painterResource("drawable/ic_settings_gear.xml"),
                        contentDescription = "Settings",
                        modifier = Modifier.size((14 * scale).dp).clickable { onOpenSettings() },
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            // Hourly view: graph selector, observations, daily, history, ending on HeaderFit's line.
            if (isHourly) buttonsSpan?.let { (groupLeft, _) ->
                Row(
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = groupLeft.dp),
                    horizontalArrangement = Arrangement.spacedBy(hourlySpacingDp.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = graphSelectorEmoji,
                        style = graphSelectorStyle,
                        color = Color.White,
                        modifier = Modifier.clickable {
                            onUpdateConfig(config.copy(viewMode = graphSelectorNextView))
                        }.testTag("graph_selector")
                    )
                    // Station observations button — ports Android's ic_thermometer drawable
                    // (drawable/ic_thermometer.xml, tinted dim white like TemperatureTouchTargets'
                    // 0xAAFFFFFF) instead of the 🌡️ emoji, so it no longer collides with the graph
                    // selector's HOURLY (🌡️) cycle hint.
                    Icon(
                        painter = painterResource("drawable/ic_thermometer.xml"),
                        contentDescription = "Weather station observations",
                        tint = Color.White.copy(alpha = 0.67f),
                        modifier = Modifier.size((15 * scale).dp).clickable {
                            onOpenObservations(targetHour.toLocalDate())
                        }.testTag("open_observations_header")
                    )
                    // Home/Daily view mode — ports Android's ic_home line icon
                    // (drawable/ic_home.xml) instead of the 🏠 emoji for parity.
                    Icon(
                        painter = painterResource("drawable/ic_home.xml"),
                        contentDescription = "Daily view",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size((15 * scale).dp).clickable {
                            onUpdateConfig(config.copy(viewMode = ViewMode.DAILY))
                        }.testTag("switch_to_daily")
                    )
                    // Forecast history (how each day's forecast evolved) — ports Android's
                    // rising line-chart icon (drawable/ic_forecast_history_line.xml),
                    // shown right of the home icon on the hourly graph. Opens on the viewed
                    // window's center date (Android: centerTime.toLocalDate()), so panning
                    // back to Wednesday and tapping opens Wednesday, not today.
                    Icon(
                        painter = painterResource("drawable/ic_forecast_history_line.xml"),
                        contentDescription = "Forecast history",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size((15 * scale).dp).clickable {
                            onOpenHistory(targetHour.toLocalDate())
                        }.testTag("open_forecast_history")
                    )
                }
            }
            // Daily view: buttons ending on HeaderFit's line, then the date (Android places both the
            // same way). Sizes/tints copied from the hourly branch so the two headers match.
            if (!isHourly) buttonsSpan?.let { (groupLeft, _) ->
                Row(
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = groupLeft.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacingDp.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Current observations are inherently now-ish, so this drops when today
                    // AND yesterday are panned off screen — matching Android's daily header.
                    if (observationsInView && fit.buttons > 0) {
                        Icon(
                            painter = painterResource("drawable/ic_thermometer.xml"),
                            contentDescription = "Weather station observations",
                            tint = Color.White.copy(alpha = 0.67f),
                            modifier = Modifier.size(iconSizeDp.dp).clickable {
                                onOpenObservations(if (todayInView) LocalDate.now() else targetHour.toLocalDate())
                            }.testTag("open_observations_header_daily")
                        )
                    }
                    if (fit.homeShown) {
                        Icon(
                            painter = painterResource("drawable/ic_home.xml"),
                            contentDescription =
                                if (homeAction == PreferredSourceHome.HomeAction.RECENTER_TODAY) {
                                    "Back to today"
                                } else {
                                    "Back to the preferred weather source"
                                },
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(iconSizeDp.dp).clickable {
                                if (homeAction == PreferredSourceHome.HomeAction.RECENTER_TODAY) {
                                    // Zoom-out is kept: homeDateOffset already moves the
                                    // window forward by it, so yesterday is the one history day.
                                    // From the months view this also returns to the daily view.
                                    onUpdateConfig(config.copy(dateOffset = homeDateOffset, monthsView = false))
                                    return@clickable
                                }
                                val preferred =
                                    PreferredSourceHome.preferredSourceId(config.effectiveSources)
                                if (preferred != null) {
                                    onUpdateConfig(
                                        config.copy(
                                            settings = config.settings.copy(weatherSource = preferred),
                                        ),
                                    )
                                    DesktopSourceViews.record(
                                        preferred,
                                        config.effectiveSources,
                                        config.viewMode,
                                        com.weatherwidget.shared.sourceview.SourceViewTrigger.HOME,
                                    )
                                }
                            }.testTag("daily_home_button")
                        )
                    }
                    if (fit.monthsShown) {
                        Icon(
                            painter = painterResource("drawable/ic_months_view.xml"),
                            contentDescription = "Months view",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(iconSizeDp.dp).clickable {
                                onUpdateConfig(
                                    config.copy(
                                        monthsView = true,
                                        monthsSpan = com.weatherwidget.shared.months.MonthsSpan.DEFAULT.months,
                                        monthsEnd = null,
                                    ),
                                )
                            }.testTag("open_months_view_daily")
                        )
                    }
                    // Opens today while today is on screen, otherwise the viewed date.
                    if (fit.buttons > 0) {
                        Icon(
                            painter = painterResource("drawable/ic_forecast_history_line.xml"),
                            contentDescription = "Forecast history",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(iconSizeDp.dp).clickable {
                                onOpenHistory(if (todayInView) LocalDate.now() else targetHour.toLocalDate())
                            }.testTag("open_forecast_history_daily")
                        )
                    }
                }
            }
            dateLeft?.let { x ->
                Text(
                    text = dateText,
                    style = dateStyle,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = x.dp).testTag("daily_header_date"),
                )
            }
        }
    }
}

/**
 * The daily header's date: always today, as on Android (`DailyHeaderResolver`: `today.format(...)`).
 * Not the header's `headerTime`, which is now + the HOURLY view's pan offset: a saved 27 h hourly
 * pan printed "Thu 22" over Wednesday's daily view (2026-10-21).
 */
internal object DesktopHeaderDate {
    fun dailyDate(now: LocalDateTime): LocalDate = now.toLocalDate()
}

/**
 * Base size (sp) the header's rain % scales from: 12, the date's size. At 15 (the old temp size)
 * the left cluster outweighed the rest of the row (user, 2026-10-22).
 */
internal const val HEADER_TEMP_BASE_SP = 12f

// Leading margins of the left cluster's pieces and the source label's end padding: drawn and
// measured from the same numbers so the fit and the layout agree.
private const val DELTA_MARGIN_DP = 2f
private const val CAPTION_MARGIN_DP = 3f
private const val PRECIP_MARGIN_DP = 6f
private const val SOURCE_LABEL_END_PADDING_DP = 6f
private const val DELTA_CAPTION = "from yest"

/** `HEADER_FIT` once per change (recomposition and resizes repeat the same plan). */
private object HeaderFitLog {
    @Volatile private var last: String? = null

    fun changed(view: String, plan: HeaderFit.Plan) {
        val line = "view=$view $plan"
        if (line == last) return
        last = line
        Log.i("HEADER_FIT", line)
    }
}

/** The current temp stays the header's largest text, above the smaller delta and rain % (user, 2026-10-22). */
internal const val HEADER_CURRENT_TEMP_SP = 17f

/**
 * The header's content from the shared [HeaderContent] rule, as Android's headers build it. The daily
 * view falls back to today's daily row for the icon when the source has no hourly row for the hour;
 * hourly views have no fallback, as on Android.
 */
@Suppress("LongParameterList")
internal fun desktopHeaderContent(
    forecast: ForecastSnapshot,
    displaySourceId: String,
    viewMode: ViewMode,
    currentTemp: Float?,
    deltaFromYesterday: Float?,
    now: LocalDateTime,
    centerTime: LocalDateTime,
    lat: Double,
    lon: Double,
    useCelsius: Boolean,
    widthDp: Int,
): HeaderContent.Content {
    val view = when (viewMode) {
        ViewMode.DAILY -> HeaderContent.View.DAILY
        ViewMode.PRECIPITATION -> HeaderContent.View.PRECIPITATION
        ViewMode.CLOUD_COVER -> HeaderContent.View.CLOUD_COVER
        else -> HeaderContent.View.TEMPERATURE
    }
    val today = forecast.raw.daily.firstOrNull { it.date == now.toLocalDate().toString() }
    val content = HeaderContent.build(
        view = view,
        hourly = forecast.raw.hourly,
        displaySourceId = displaySourceId,
        dailyFallback = today?.takeIf { view == HeaderContent.View.DAILY }?.let {
            HeaderContent.DailyFallback(it.source ?: displaySourceId, it.iconToken, it.condition, it.precipProbability)
        },
        fallbackDailyPrecip = today?.precipProbability,
        currentTemp = currentTemp,
        isStaleEstimate = false,
        // The popup is always wider than a one-column widget: one decimal, like Android's ≥ 2 columns.
        numColumns = DESKTOP_HEADER_COLUMNS,
        deltaFromYesterday = deltaFromYesterday,
        now = now,
        centerTime = centerTime,
        lat = lat,
        lon = lon,
        useCelsius = useCelsius,
        widthDp = widthDp,
    )
    HeaderContentLog.changed(displaySourceId, view, content)
    return content
}

private const val DESKTOP_HEADER_COLUMNS = 5

/** `HEADER_CONTENT` once per change (recomposition repeats the same). */
private object HeaderContentLog {
    @Volatile private var last: String? = null

    fun changed(sourceId: String, view: HeaderContent.View, c: HeaderContent.Content) {
        val line = "source=$sourceId view=$view icon=${c.iconName} temp=${c.tempText} delta=${c.deltaText} " +
            "precip=${c.precipText} label=${c.sourceLabel}"
        if (line == last) return
        last = line
        Log.i("HEADER_CONTENT", line)
    }
}
