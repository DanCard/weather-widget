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
    // Measures the daily header's date so the centre cluster can decide whether it still fits
    // beside the buttons — see DailyHeaderCentreFit.
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
    // The 15-minute nowcast beside the rain % (daily view, Open-Meteo, native coverage only).
    // Keyed on the forecast too: a nowcast fetch ends in the same data-updated reload.
    val nowcastText = remember(forecast.raw, displaySource, nowLocal, isHourly, config.lat, config.lon) {
        if (isHourly) null else DesktopNowcastView.headerText(config)
    }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val headerWidthDp = with(density) { windowInfo.containerSize.width.toDp().value.toInt() }
    // Icon, temp, delta, rain % and source label: the shared header rule Android's headers use
    // (plans/261021-header-shared-content-audit.md).
    val content = remember(forecast.raw, displaySource, displayTemp, deltaVal, nowLocal, targetHour, config.viewMode, config.lat, config.lon, config.settings.useCelsius, nowcastText, headerWidthDp) {
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
            nowcastText = nowcastText,
        )
    }

    val toggleWeatherSource = { cycleDisplaySource(config, onUpdateConfig) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { toggleWeatherSource() }
    ) {
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
                    content.iconName?.let { iconName ->
                        androidx.compose.foundation.Image(
                            painter = WeatherIcon.painterForIconName(iconName),
                            contentDescription = null,
                            modifier = Modifier.size((22 * scale).dp).padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = content.tempText ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                        fontSize = (15 * scale).sp
                    )
                }
                content.deltaText?.let { deltaText ->
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = deltaText,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (11 * scale).sp,
                        color = Color(0xFFFF6B35),
                        modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    // Caption clarifying the delta's meaning; smaller/dimmer than the delta itself.
                    // Single-line and non-wrapping: two stacked half-words explain nothing, and the
                    // cluster is content-sized now, so it always has the room to render in full.
                    Text(
                        text = "from yest",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (9 * scale).sp,
                        color = Color(0xFFFF6B35).copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                    )
                }
                val precipText = content.precipText
                val precipFontScale = content.precipFontScale
                if (precipText != null && precipFontScale != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = precipText,
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = (HEADER_TEMP_BASE_SP * precipFontScale * scale).sp,
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

            // Center cluster: view-switch icons when hourly, else date text.
            //
            // The only weighted cluster, so it takes whatever the two content-sized clusters leave
            // and centres itself in THAT span — i.e. it sits at the true window centre when the
            // header is uncrowded, and slides right as the left cluster grows. Giving the icons'
            // slack away is what buys the left cluster its full width.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isHourly) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((12 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cycling graph selector
                        val currentView = config.viewMode
                        val (nextEmoji, nextView) = when (currentView) {
                            ViewMode.CLOUD_COVER -> "🌧️" to ViewMode.PRECIPITATION
                            ViewMode.PRECIPITATION -> "🌡️" to ViewMode.TEMPERATURE
                            else -> "☁️" to ViewMode.CLOUD_COVER
                        }
                        Text(
                            text = nextEmoji,
                            fontSize = (13 * scale).sp,
                            color = Color.White,
                            modifier = Modifier.clickable {
                                onUpdateConfig(config.copy(viewMode = nextView))
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
                } else {
                    // Daily view: buttons, then date. Sizes/tints copied from the hourly branch so
                    // the two headers match. Buttons then date, matching Android's daily header.
                    // Android cannot reliably fit the date to the LEFT of its centred buttons (the
                    // left cluster reaches past it on real widgets), so both platforms settle on
                    // this order rather than disagreeing.
                    val iconSizeDp = 15 * scale
                    val spacingDp = 14 * scale
                    // The home button never changes the view (the daily view is already home in
                    // that sense). It resets the SOURCE when another one is shown, else — on the
                    // preferred source with today panned off screen — brings back yesterday + today.
                    val homeAction = PreferredSourceHome.homeAction(
                        currentSourceId = config.displaySource,
                        visibleSourceIds = config.effectiveSources,
                        todayInView = todayInView,
                    )
                    val showHomeButton = homeAction != null
                    val dateText = DesktopHeaderDate.dailyDate(nowLocal).format(dateFormatter)
                    val dateStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (12 * scale).sp)
                    val dateWidthDp = remember(dateText, dateStyle, headerTextMeasurer, density) {
                        with(density) { headerTextMeasurer.measure(dateText, dateStyle).size.width.toDp().value }
                    }
                    // Months view (zoom out), as on Android's daily header; hidden while it is open
                    // (the home button brings the daily view back) or disabled in settings.
                    val showMonthsButton = !config.monthsView && config.settings.showDailyZoomOutButton
                    val iconCount = (if (observationsInView) 1 else 0) + (if (showHomeButton) 1 else 0) +
                        (if (showMonthsButton) 1 else 0) + 1
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Buttons never yield; the date does. Same priority Android's header lands
                        // on, where the painted date reserves space around the icon count and drops
                        // itself when the row has none left.
                        val showDate = DailyHeaderCentreFit.showDate(
                            availableDp = maxWidth.value,
                            iconCount = iconCount,
                            iconSizeDp = iconSizeDp,
                            spacingDp = spacingDp,
                            dateWidthDp = dateWidthDp,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacingDp.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Current observations are inherently now-ish, so this drops when today
                            // AND yesterday are panned off screen — matching Android's daily header.
                            if (observationsInView) {
                                Icon(
                                    painter = painterResource("drawable/ic_thermometer.xml"),
                                    contentDescription = "Weather station observations",
                                    tint = Color.White.copy(alpha = 0.67f),
                                    modifier = Modifier.size(iconSizeDp.dp).clickable {
                                        onOpenObservations(if (todayInView) LocalDate.now() else targetHour.toLocalDate())
                                    }.testTag("open_observations_header_daily")
                                )
                            }
                            if (showHomeButton) {
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
                            if (showMonthsButton) {
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
                            Icon(
                                painter = painterResource("drawable/ic_forecast_history_line.xml"),
                                contentDescription = "Forecast history",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(iconSizeDp.dp).clickable {
                                    onOpenHistory(if (todayInView) LocalDate.now() else targetHour.toLocalDate())
                                }.testTag("open_forecast_history_daily")
                            )
                            if (showDate) {
                                Text(
                                    text = dateText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = (12 * scale).sp,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.testTag("daily_header_date"),
                                )
                            }
                        }
                    }
                }
            }

            // Right cluster: API source + Settings gear. Content-sized, like the left cluster —
            // it is pinned to the right edge by the centre cluster's weight, not by a width share.
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
                        }.padding(end = 6.dp)
                    )
                } else {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (10 * scale).sp,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 6.dp)
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

/** Base size (sp) the header's rain % scales from — the current temp's size. */
internal const val HEADER_TEMP_BASE_SP = 15f

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
    nowcastText: String? = null,
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
        nowcastText = nowcastText,
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
