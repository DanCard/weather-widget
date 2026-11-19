package com.weatherwidget.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.onClick
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.shared.observations.RadarNowSample
import com.weatherwidget.shared.observations.RadarNowSampler
import com.weatherwidget.shared.observations.RainNow
import com.weatherwidget.shared.util.GraphSelector
import com.weatherwidget.shared.util.HeaderButton
import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderButtonZone
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
    /** Opens the Current Observations window for the viewed day (a past day lets its refresh refill stale Google hours). */
    onOpenCurrentObservations: (LocalDate) -> Unit,
    onOpenComparison: (viewedDate: LocalDate) -> Unit = {},
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
    // Rain falling now (station, radar, Tomorrow.io when displayed): the icon becomes blue dots
    // (plans/261115-header-rain-now-dots.md). Re-read each minute: the daemon writes the radar sample.
    val rainNow = remember(forecast.raw, forecast.sourceId, config.lat, config.lon, nowEpoch / 60_000L) {
        desktopRainNow(forecast, config.lat, config.lon, nowEpoch)
    }
    val content = remember(forecast.raw, forecast.sourceId, displaySource, displayTemp, deltaVal, nowLocal, targetHour, config.viewMode, config.lat, config.lon, config.settings.useCelsius, config.settings.headerRainLookaheadHours, headerWidthDp, rainNow) {
        desktopHeaderContent(
            forecast = forecast,
            // The label moves on the click; the rest waits for that source's snapshot.
            displaySourceId = forecast.sourceId,
            labelSourceId = displaySource.id,
            viewMode = config.viewMode,
            currentTemp = displayTemp,
            deltaFromYesterday = deltaVal,
            now = nowLocal,
            centerTime = targetHour,
            lat = config.lat,
            lon = config.lon,
            useCelsius = config.settings.useCelsius,
            widthDp = headerWidthDp,
            rainNow = rainNow,
            rainLookaheadHours = config.settings.headerRainLookaheadHours,
        )
    }

    val cycleSourceForward = { cycleDisplaySource(config, forward = true, onUpdateConfig = onUpdateConfig) }
    val cycleSourceBackward = { cycleDisplaySource(config, forward = false, onUpdateConfig = onUpdateConfig) }

    // Daily header inputs, decided before layout so the shared fit can weigh every item.
    val iconSizeDp = CENTRE_ICON_DP * scale
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
    // hourly alike: the hourly row has no date.
    // Cycling graph selector: the emoji names the view it switches to.
    val graphSelectorEmoji = GraphSelector.emoji(config.viewMode)
    // Which buttons the row wants (HeaderButtons): hourly observations only while the graph shows
    // some of today, since the button opens current readings.
    val buttonsWant = if (isHourly) {
        HeaderButtons.hourly(
            observationsInView = HeaderButtons.hourlyObservationsInView(
                centre = targetHour,
                backHours = DesktopGraphUtils.backHoursFor(config.zoomFactor).toLong(),
                forwardHours = DesktopGraphUtils.forwardHoursFor(config.zoomFactor).toLong(),
                today = nowLocal.toLocalDate(),
            ),
        )
    } else {
        HeaderButtons.daily(observationsInView = observationsInView, home = homeAction != null, months = wantMonthsButton)
    }
    val graphSelectorStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (13 * scale).sp)
    val sourceWidthDp = remember(content.sourceLabel, config.effectiveSources, config.viewMode, nowLocal, targetHour, headerWidthDp, sourceStyle, headerTextMeasurer, density) {
        val labels = (config.effectiveSources.map {
            HeaderContent.sourceLabel(desktopHeaderView(config.viewMode), WeatherSource.fromDisplaySource(it).id, nowLocal, targetHour, headerWidthDp)
        } + content.sourceLabel).distinct()
        with(density) { labels.maxOf { headerTextMeasurer.measure(it, sourceStyle).size.width.toDp().value } }
    }
    val nominalDateWidthDp = remember(dateText, dateStyle, headerTextMeasurer, density) {
        with(density) { headerTextMeasurer.measure(dateText, dateStyle).size.width.toDp().value }
    }
    val sourceGearGapDp = 12f * scale
    val gearWidthDp = 14f * scale
    val headerMeasured = remember(content, scale, isHourly, buttonsWant, dateText, headerTextMeasurer, density, headerWidthDp, sourceWidthDp, nominalDateWidthDp) {
        fun widthDp(text: String, style: androidx.compose.ui.text.TextStyle) =
            with(density) { headerTextMeasurer.measure(text, style).size.width.toDp().value }
        HeaderFit.Measured(
            width = 0f, // the BoxWithConstraints below supplies it
            rightCluster = sourceWidthDp + sourceGearGapDp + gearWidthDp,
            gap = spacingDp,
            temp = widthDp(content.tempText ?: "—", tempStyle),
            rain = content.precipText?.takeIf { content.precipFontScale != null }
                ?.let { PRECIP_MARGIN_DP + widthDp(it, precipStyle) } ?: 0f,
            delta = content.deltaText?.let { DELTA_MARGIN_DP + widthDp(it, deltaStyle) } ?: 0f,
            icon = if (content.iconName != null) 18 * scale else 0f,
            date = if (isHourly) 0f else nominalDateWidthDp,
            caption = if (content.deltaText != null) CAPTION_MARGIN_DP + widthDp(DELTA_CAPTION, captionStyle) else 0f,
            baseButtons = buttonsWant.baseButtons,
            wantHome = buttonsWant.wantHome,
            wantMonths = buttonsWant.wantMonths,
            buttonWidth = HeaderButtonZone.zoneDp(CENTRE_ICON_DP, headerWidthDp / scale) * scale,
            minButtonWidth = HeaderButtonZone.minZoneDp(CENTRE_ICON_DP) * scale,
        )
    }

    var sourcePressed by remember { mutableStateOf(false) }
    val latestForward by rememberUpdatedState(cycleSourceForward)
    val latestBackward by rememberUpdatedState(cycleSourceBackward)
    val canCycleSources = config.effectiveSources.size > 1

    fun Modifier.switchSourceOnPress(enabled: Boolean): Modifier =
        if (!enabled) this else this.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                            val change = event.changes.firstOrNull()
                            if (change != null && !change.isConsumed) {
                                sourcePressed = true
                                change.consume()
                                if (event.buttons.isSecondaryPressed) {
                                    latestBackward()
                                } else if (event.buttons.isPrimaryPressed) {
                                    latestForward()
                                }
                            }
                        }
                        androidx.compose.ui.input.pointer.PointerEventType.Release,
                        androidx.compose.ui.input.pointer.PointerEventType.Exit -> {
                            sourcePressed = false
                        }
                    }
                }
            }
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .switchSourceOnPress(enabled = canCycleSources)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measuredWidth = maxWidth.value
            val unscaledWidthDp = measuredWidth / scale
            val buttonWidth = HeaderButtonZone.zoneDp(CENTRE_ICON_DP, unscaledWidthDp) * scale
            val minButtonWidth = HeaderButtonZone.minZoneDp(CENTRE_ICON_DP) * scale
            val measured = remember(headerMeasured, measuredWidth, buttonWidth, minButtonWidth) {
                headerMeasured.copy(
                    width = measuredWidth,
                    buttonWidth = buttonWidth,
                    minButtonWidth = minButtonWidth,
                )
            }
            val fit = remember(measured) {
                HeaderFit.resolve(measured).also { HeaderFitLog.changed(if (isHourly) "hourly" else "daily", scale, it, measured) }
            }
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
                    // No icon when an hourly view's source has no row for this hour (HeaderContent).
                    // Active rain-now dots open hourly rain chance; other weather icons keep the
                    // normal temp/daily toggle.
                    content.iconName?.takeIf { fit.showIcon }?.let { iconName ->
                        val iconTargetMode = if (content.isRainingNow) {
                            when (HeaderContent.rainNowTapTarget(desktopHeaderView(config.viewMode))) {
                                HeaderContent.View.PRECIPITATION -> ViewMode.PRECIPITATION
                                else -> ViewMode.DAILY
                            }
                        } else {
                            if (isHourly) ViewMode.DAILY else ViewMode.TEMPERATURE
                        }
                        androidx.compose.foundation.Image(
                            painter = WeatherIcon.painterForIconName(iconName),
                            contentDescription = null,
                            modifier = Modifier
                                .size((18 * scale).dp)
                                .clickable { onUpdateConfig(config.copy(viewMode = iconTargetMode)) }
                                .padding(end = 4.dp)
                                .testTag("header_weather_icon")
                        )
                    }
                    if (fit.showTemp) {
                        Text(
                            text = content.tempText ?: "—",
                            style = tempStyle,
                            modifier = Modifier
                                .clickable {
                                    val targetMode = if (isHourly) ViewMode.DAILY else ViewMode.TEMPERATURE
                                    onUpdateConfig(config.copy(viewMode = targetMode))
                                }
                                .testTag("current_temp_toggle")
                        )
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

                // Right cluster: API source label + Settings gear pinned to the right edge.
                val visibleSources = config.effectiveSources
                Text(
                    text = content.sourceLabel,
                    style = sourceStyle,
                    color = Color.White.copy(
                        alpha = when {
                            visibleSources.size <= 1 -> 0.5f
                            sourcePressed -> 1.0f
                            else -> 0.6f
                        },
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .offset(y = (-1.5f * scale).dp)
                        .switchSourceOnPress(enabled = visibleSources.size > 1)
                        .testTag("header_source_label"),
                )
                Spacer(Modifier.width(sourceGearGapDp.dp))
                Icon(
                    painter = painterResource("drawable/ic_settings_gear.xml"),
                    contentDescription = "Settings",
                    modifier = Modifier.size(gearWidthDp.dp).clickable { onOpenSettings() },
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
            // Action buttons in HeaderButtons.ORDER (home first, comparison last), right-anchored
            // before the right cluster.
            buttonsSpan?.let { (slotLeft, _) ->
                val zoneDp = fit.buttonZone.takeIf { it > 0f } ?: measured.buttonWidth
                val shownButtons = HeaderButtons.shown(buttonsWant, fit)
                // A little taller than the icons, so a click just above or below one still lands.
                val buttonHeightDp = iconSizeDp + 8 * scale
                fun onClick(button: HeaderButton): () -> Unit = when (button) {
                    HeaderButton.HOME -> if (isHourly) {
                        { onUpdateConfig(config.copy(viewMode = ViewMode.DAILY)) }
                    } else {
                        {
                            if (homeAction == PreferredSourceHome.HomeAction.RECENTER_TODAY) {
                                // Zoom-out is kept: homeDateOffset already moves the window forward
                                // by it, so yesterday is the one history day. From the months view
                                // this also returns to the daily view.
                                onUpdateConfig(config.copy(dateOffset = homeDateOffset, monthsView = false))
                            } else {
                                val preferred = PreferredSourceHome.preferredSourceId(config.effectiveSources)
                                if (preferred != null) {
                                    DesktopSourceSwitch.switchTo(
                                        config,
                                        preferred,
                                        com.weatherwidget.shared.sourceview.SourceViewTrigger.HOME,
                                        onUpdateConfig,
                                    )
                                }
                            }
                        }
                    }
                    // The date only scopes the window's Refresh (a previous day's Google history
                    // refill), as for history below.
                    HeaderButton.OBSERVATIONS -> {
                        { onOpenCurrentObservations(if (!isHourly && todayInView) LocalDate.now() else targetHour.toLocalDate()) }
                    }
                    HeaderButton.GRAPH_SELECTOR -> {
                        { onUpdateConfig(config.copy(viewMode = GraphSelector.next(config.viewMode))) }
                    }
                    HeaderButton.MONTHS -> {
                        {
                            onUpdateConfig(
                                config.copy(
                                    monthsView = true,
                                    monthsSpan = com.weatherwidget.shared.months.MonthsSpan.DEFAULT.months,
                                    monthsEnd = null,
                                ),
                            )
                        }
                    }
                    // Hourly: the viewed window's centre date (Android: centerTime.toLocalDate()), so
                    // panning back to Wednesday opens Wednesday. Daily: today while today is on
                    // screen, otherwise the viewed date.
                    HeaderButton.COMPARISON -> {
                        { onOpenComparison(if (!isHourly && todayInView) LocalDate.now() else targetHour.toLocalDate()) }
                    }
                }
                fun onSecondaryClick(button: HeaderButton): (() -> Unit)? = when (button) {
                    HeaderButton.GRAPH_SELECTOR -> {
                        { onUpdateConfig(config.copy(viewMode = GraphSelector.previous(config.viewMode))) }
                    }
                    else -> null
                }
                Row(
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = slotLeft.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (button in shownButtons) {
                        val secondary = onSecondaryClick(button)
                        Box(
                            modifier = Modifier
                                .size(width = zoneDp.dp, height = buttonHeightDp.dp)
                                .clickable(onClick = onClick(button))
                                .then(
                                    if (secondary != null) {
                                        @OptIn(ExperimentalFoundationApi::class)
                                        Modifier.onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = secondary)
                                    } else Modifier
                                )
                                .testTag(headerButtonTag(button, isHourly)),
                            contentAlignment = Alignment.Center,
                        ) { HeaderButtonGlyph(button, isHourly, homeAction, iconSizeDp, graphSelectorEmoji, graphSelectorStyle) }
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

// Leading margins of the left cluster's pieces: drawn and measured from the same numbers so the
// fit and the layout agree.
private const val DELTA_MARGIN_DP = 2f
private const val CAPTION_MARGIN_DP = 3f
private const val PRECIP_MARGIN_DP = 6f
private const val DELTA_CAPTION = "from yest"

/** [HeaderFit.LOG_TAG] once per plan change (recomposition and resizes repeat the same plan). */
private object HeaderFitLog {
    @Volatile private var last: String? = null

    fun changed(view: String, scale: Float, plan: HeaderFit.Plan, measured: HeaderFit.Measured) {
        val key = "view=$view $plan"
        if (key == last) return
        last = key
        Log.i(HeaderFit.LOG_TAG, HeaderFit.logLine(view, measured.width, scale, plan, measured))
    }
}

/**
 * A header button's test tag, on its zone (the click target); its icon carries the same tag plus
 * `_icon`, for tests that need where it is drawn.
 */
internal fun headerButtonTag(button: HeaderButton, isHourly: Boolean): String = when (button) {
    HeaderButton.HOME -> if (isHourly) "switch_to_daily" else "daily_home_button"
    HeaderButton.OBSERVATIONS -> if (isHourly) "open_observations_header" else "open_observations_header_daily"
    HeaderButton.GRAPH_SELECTOR -> "graph_selector"
    HeaderButton.MONTHS -> "open_months_view_daily"
    HeaderButton.COMPARISON -> if (isHourly) "open_forecast_comparison" else "open_forecast_comparison_daily"
}

/** One header button's icon, drawn centred in its zone (the zone is the click target). */
@Composable
private fun HeaderButtonGlyph(
    button: HeaderButton,
    isHourly: Boolean,
    homeAction: PreferredSourceHome.HomeAction?,
    iconSizeDp: Float,
    graphSelectorEmoji: String,
    graphSelectorStyle: androidx.compose.ui.text.TextStyle,
) {
    val iconTag = headerButtonTag(button, isHourly) + "_icon"
    val iconModifier = Modifier.size(iconSizeDp.dp).testTag(iconTag)
    when (button) {
        HeaderButton.HOME -> Icon(
            painter = painterResource("drawable/ic_home.xml"),
            contentDescription = when {
                isHourly -> "Daily view"
                homeAction == PreferredSourceHome.HomeAction.RECENTER_TODAY -> "Back to today"
                else -> "Back to the preferred weather source"
            },
            tint = Color.White.copy(alpha = 0.6f),
            modifier = iconModifier,
        )
        // Current observations: ic_thermometer, not the 🌡️ emoji the graph selector uses for its
        // temperature hint.
        HeaderButton.OBSERVATIONS -> Icon(
            painter = painterResource("drawable/ic_thermometer.xml"),
            contentDescription = "Weather station observations",
            tint = Color.White.copy(alpha = 0.67f),
            modifier = iconModifier,
        )
        HeaderButton.GRAPH_SELECTOR -> Text(
            text = graphSelectorEmoji,
            style = graphSelectorStyle,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true).testTag(iconTag),
        )
        HeaderButton.MONTHS -> Icon(
            painter = painterResource("drawable/ic_months_view.xml"),
            contentDescription = "Months view",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = iconModifier,
        )
        HeaderButton.COMPARISON -> Icon(
            painter = painterResource("drawable/ic_forecast_comparison_line.xml"),
            contentDescription = "Forecast comparison",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = iconModifier,
        )
    }
}

/** A header button's icon, before the header scale; its zone adds `HeaderButtonZone`'s air. */
private const val CENTRE_ICON_DP = 15f

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
    rainNow: RainNow.Level?,
    labelSourceId: String = displaySourceId,
    rainLookaheadHours: Int = com.weatherwidget.shared.settings.DisplaySettings.HEADER_RAIN_LOOKAHEAD.default,
): HeaderContent.Content {
    val view = desktopHeaderView(viewMode)
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
        rainNow = rainNow,
        labelSourceId = labelSourceId,
        rainLookaheadHours = rainLookaheadHours,
    )
    HeaderContentLog.changed(displaySourceId, view, content)
    return content
}

private const val DESKTOP_HEADER_COLUMNS = 5

/**
 * The header's rain-now level from the snapshot's observations (every API at the site), the radar
 * sample the daemon stored and the snapshot's source; [RainNow] logs a change of level once.
 */
internal fun desktopRainNow(
    forecast: ForecastSnapshot,
    lat: Double,
    lon: Double,
    nowMs: Long,
    radarStore: RadarNowSampler.Store = DesktopRadarNow.store,
): RainNow.Level? {
    val verdict = RainNow.resolve(
        readings = forecast.raw.rawObservations,
        radar = RadarNowSample.atSite(radarStore.read(), lat, lon),
        displaySourceId = forecast.sourceId,
        nowMs = nowMs,
    )
    RainNow.logTransition(verdict, "desktop", nowMs)
    return verdict.level
}

private fun desktopHeaderView(viewMode: ViewMode): HeaderContent.View = when (viewMode) {
    ViewMode.DAILY -> HeaderContent.View.DAILY
    ViewMode.PRECIPITATION -> HeaderContent.View.PRECIPITATION
    ViewMode.CLOUD_COVER -> HeaderContent.View.CLOUD_COVER
    else -> HeaderContent.View.TEMPERATURE
}

/** [HeaderContent.LOG_TAG] once per change (recomposition repeats the same). */
private object HeaderContentLog {
    @Volatile private var last: String? = null

    fun changed(sourceId: String, view: HeaderContent.View, c: HeaderContent.Content) {
        val line = HeaderContent.logLine(sourceId, view, c)
        if (line == last) return
        last = line
        Log.i(HeaderContent.LOG_TAG, line)
    }
}
