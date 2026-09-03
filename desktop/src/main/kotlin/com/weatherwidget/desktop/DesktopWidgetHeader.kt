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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.WeatherSource
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
    onOpenObservations: () -> Unit,
    onOpenHistory: (viewedDate: LocalDate) -> Unit = {},
    onUpdateLocation: () -> Unit,
    headerTime: LocalDateTime = LocalDateTime.now(),
    scale: Float = 1f,
    /** Whether today is among the days the daily view is rendering; drives the history target date. */
    todayInView: Boolean = true,
    /** Whether today or yesterday is on screen; drives the current-observations button. */
    observationsInView: Boolean = true,
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
    val displaySource = remember(config.settings.weatherSource) {
        WeatherSource.fromDisplaySource(config.settings.weatherSource)
    }
    val displayTemp = resolvedCurrentTemp ?: forecast.resolved.currentTemp
    // The header shows the DELTA FROM YESTERDAY (observed vs blended actual 24h earlier). It is
    // pan-independent, so it always shows when it exists and clears the noise threshold — no
    // graph-window gate, matching Android's post-swap header.
    val deltaVal = resolvedDeltaFromYesterday ?: forecast.resolved.deltaFromYesterday
    val deltaTemp = deltaVal?.takeIf { kotlin.math.abs(it) >= 0.1f }

    val todayForecast = remember(forecast.raw.daily, nowLocal) {
        forecast.raw.daily.firstOrNull { it.date == nowLocal.toLocalDate().toString() }
    }
    val isHourly = config.viewMode.isHourly
    val headerPrecipitation = remember(
        forecast.raw.hourly,
        displaySource,
        todayForecast,
        nowLocal,
        isHourly,
        config.lat,
        config.lon,
    ) {
        DesktopHeaderPrecipitationResolver.resolve(
            hourlyForecasts = forecast.raw.hourly,
            displaySource = displaySource,
            fallbackDailyProbability = todayForecast?.precipProbability,
            referenceTime = nowLocal,
            latitude = config.lat,
            longitude = config.lon,
            isDailyView = !isHourly,
        )
    }
    val precipProb = headerPrecipitation.probability
    val precipFontScale = headerPrecipitation.fontScale

    val toggleWeatherSource = {
        val visibleSources = config.settings.visibleSources
        if (visibleSources.size > 1) {
            val nextIdx = (visibleSources.indexOf(config.settings.weatherSource) + 1) % visibleSources.size
            onUpdateConfig(config.copy(settings = config.settings.copy(weatherSource = visibleSources[nextIdx])))
        }
    }

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
                        val targetMode = if (isHourly) ViewMode.DAILY else ViewMode.HOURLY
                        onUpdateConfig(config.copy(viewMode = targetMode))
                    }.testTag("current_temp_toggle")
                ) {
                    androidx.compose.foundation.Image(
                        painter = WeatherIcon.painter(forecast.resolved.currentCondition),
                        contentDescription = null,
                        modifier = Modifier.size((22 * scale).dp).padding(end = 4.dp)
                    )
                    Text(
                        text = displayTemp?.let { formatTrayTemperature(it, config.settings.useCelsius) + "°" } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                        fontSize = (15 * scale).sp
                    )
                }
                if (deltaTemp != null) {
                    Spacer(Modifier.width(2.dp))
                    val displayDelta = if (config.settings.useCelsius) deltaTemp / 1.8f else deltaTemp
                    Text(
                        text = String.format(Locale.US, "%+.1f", displayDelta),
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
                if (precipProb != null && precipFontScale != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$precipProb%",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = (DesktopHeaderPrecipitationResolver.HEADER_TEMP_BASE_SP * precipFontScale * scale).sp,
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
                        horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cycling graph selector
                        val currentView = config.viewMode
                        val (nextEmoji, nextView) = when (currentView) {
                            ViewMode.CLOUD_COVER -> "🌧️" to ViewMode.PRECIPITATION
                            ViewMode.PRECIPITATION -> "🌡️" to ViewMode.HOURLY
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
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_thermometer.xml"),
                            contentDescription = "Weather station observations",
                            tint = Color.White.copy(alpha = 0.67f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onOpenObservations()
                            }.testTag("open_observations_header")
                        )
                        // Home/Daily view mode — ports Android's ic_home line icon
                        // (drawable/ic_home.xml) instead of the 🏠 emoji for parity.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_home.xml"),
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
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_forecast_history_line.xml"),
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
                    val spacingDp = 10 * scale
                    // The home button here resets the SOURCE, not the view: the daily view is
                    // already home in the view sense, and the one axis it can still be off home on
                    // is the API indicator having been tapped away from the preferred source.
                    val showHomeButton = PreferredSourceHome.shouldShowHomeButton(
                        currentSourceId = config.settings.weatherSource,
                        visibleSourceIds = config.settings.visibleSources,
                    )
                    val dateText = targetHour.format(dateFormatter)
                    val dateStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (12 * scale).sp)
                    val density = LocalDensity.current
                    val dateWidthDp = remember(dateText, dateStyle, headerTextMeasurer, density) {
                        with(density) { headerTextMeasurer.measure(dateText, dateStyle).size.width.toDp().value }
                    }
                    val iconCount = (if (observationsInView) 1 else 0) + (if (showHomeButton) 1 else 0) + 1
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
                                    painter = androidx.compose.ui.res.painterResource("drawable/ic_thermometer.xml"),
                                    contentDescription = "Weather station observations",
                                    tint = Color.White.copy(alpha = 0.67f),
                                    modifier = Modifier.size(iconSizeDp.dp).clickable {
                                        onOpenObservations()
                                    }.testTag("open_observations_header_daily")
                                )
                            }
                            if (showHomeButton) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource("drawable/ic_home.xml"),
                                    contentDescription = "Back to the preferred weather source",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(iconSizeDp.dp).clickable {
                                        val preferred =
                                            PreferredSourceHome.preferredSourceId(config.settings.visibleSources)
                                        if (preferred != null) {
                                            onUpdateConfig(
                                                config.copy(
                                                    settings = config.settings.copy(weatherSource = preferred),
                                                ),
                                            )
                                        }
                                    }.testTag("daily_home_button")
                                )
                            }
                            // Opens today while today is on screen, otherwise the viewed date.
                            Icon(
                                painter = androidx.compose.ui.res.painterResource("drawable/ic_forecast_history_line.xml"),
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
                val visibleSources = config.settings.visibleSources
                // Show the shared short label (e.g. "Meteo"), matching Android's API indicator, rather
                // than the raw stored id ("OPEN_METEO"). One source of truth: WeatherSource.shortDisplayName.
                val sourceLabel = WeatherSource.fromDisplaySource(config.settings.weatherSource).shortDisplayName
                if (visibleSources.size > 1) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (10 * scale).sp,
                        modifier = Modifier.clickable {
                            val nextIdx = (visibleSources.indexOf(config.settings.weatherSource) + 1) % visibleSources.size
                            onUpdateConfig(config.copy(settings = config.settings.copy(weatherSource = visibleSources[nextIdx])))
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
                    painter = androidx.compose.ui.res.painterResource("drawable/ic_settings_gear.xml"),
                    contentDescription = "Settings",
                    modifier = Modifier.size((14 * scale).dp).clickable { onOpenSettings() },
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}
