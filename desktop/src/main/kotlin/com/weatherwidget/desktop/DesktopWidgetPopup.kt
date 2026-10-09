package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import com.weatherwidget.shared.util.FailureBannerStage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.weatherwidget.shared.graph.FailureBannerLayout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.NoHourlyChecker
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.data.remote.HourlyOnDemand
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

private const val TAG = "Main"
private const val MIN_HOURLY_OFFSET = -720
private const val MAX_HOURLY_OFFSET = 720

@Composable
internal fun WidgetPopup(
    config: DesktopConfig,
    forecast: ForecastSnapshot?,
    dataStatus: DataStatus,
    resolvedCurrentTemp: Float? = null,
    resolvedDeltaFromYesterday: Float? = null,
    onUpdateLocation: () -> Unit,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
    onOpenHistory: (viewedDate: LocalDate) -> Unit = {},
    onRegisterArrowKeyHandler: (((left: Boolean) -> Boolean)?) -> Unit = {},
    onNeedHistory: (Int) -> Unit = {},
    onNeedHourlyRefresh: (date: LocalDate, onComplete: (List<HourlyForecast>) -> Unit) -> Unit = { _, _ -> },
    onDayClickAudit: (String) -> Unit = {},
    transientMessage: String? = null,
    currentTempFetchError: String? = null,
    currentTempFetchPill: DesktopFailurePill? = null,
    currentTempFetchIsWarmup: Boolean = false,
    onDismissCurrentTempError: () -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      // One shared scale for header + graph so everything grows together with the window.
      // Density-independent (maxHeight and 320.dp both carry density). ~2x at a typical window.
      val uiScale = (maxHeight / 320.dp).coerceIn(1f, 3f)
      Surface(modifier = Modifier.fillMaxSize()) {
        when (dataStatus) {
            is DataStatus.Error -> CenteredMessage(dataStatus.message)
            is DataStatus.Loading -> CenteredMessage("Loading…")
            is DataStatus.FetchingLocation -> CenteredMessage("Getting weather for ${dataStatus.placeName}…")
            is DataStatus.NoData -> CenteredMessage("Tap to configure")
            is DataStatus.Live, is DataStatus.Stale -> {
                val snapshot = forecast ?: return@Surface
                // Published by the daily branch below from the days it actually renders. The header
                // is composed before the daily surface measures itself, so this is reported upward
                // rather than recomputed here: the daily column count depends on the graph area's
                // width, and zoom-out prepends history columns that `getVisibleDateRange` knows
                // nothing about (see DesktopDailyForecastModel.build). Defaults to true so the
                // first frame shows both buttons rather than flashing one in.
                var dailyTodayInView by remember { mutableStateOf(true) }
                var dailyObservationsInView by remember { mutableStateOf(true) }
                val toggleWeatherSource = {
                    val visibleSources = config.effectiveSources
                    if (visibleSources.size > 1) {
                        val nextIdx = (visibleSources.indexOf(config.displaySource) + 1) % visibleSources.size
                        onUpdateConfig(config.copy(settings = config.settings.copy(weatherSource = visibleSources[nextIdx])))
                    }
                }
                Column(modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 2.dp)) {
                    WidgetHeader(
                        config = config,
                        forecast = snapshot,
                        resolvedCurrentTemp = resolvedCurrentTemp,
                        resolvedDeltaFromYesterday = resolvedDeltaFromYesterday,
                        onUpdateConfig = onUpdateConfig,
                        onOpenSettings = onOpenSettings,
                        onOpenObservations = onOpenObservations,
                        onOpenHistory = onOpenHistory,
                        onUpdateLocation = onUpdateLocation,
                        headerTime = LocalDateTime.now().plusHours(config.hourlyOffset.toLong()),
                        scale = uiScale,
                        todayInView = dailyTodayInView,
                        observationsInView = dailyObservationsInView,
                    )

                    Spacer(Modifier.height(4.dp))

                    // Banner for a day-tap whose hourly data is missing: "Fetching…" over the day's
                    // empty hourly graph while its on-demand fetch runs (held until it completes),
                    // then the result if the data is still missing (auto-dismissed). Declared above
                    // both branches: the tap starts in the daily view and lands in the hourly one.
                    var noHourlyMessage by remember { mutableStateOf<String?>(null) }
                    var noHourlyFetching by remember { mutableStateOf(false) }
                    LaunchedEffect(noHourlyMessage, noHourlyFetching) {
                        if (noHourlyMessage != null && !noHourlyFetching) {
                            kotlinx.coroutines.delay(NoHourlyChecker.MESSAGE_DURATION_MS)
                            noHourlyMessage = null
                        }
                    }
                    val hourlySourceIds = config.effectiveSources.toSet()
                    // A day tap or a pan settling on a day Google has not covered: fetch that day
                    // under the "Fetching…" banner (HourlyOnDemand); the banner clears when its hours
                    // arrive, else says where the data ends.
                    val startHourlyFetch: (LocalDate) -> Unit = { date ->
                        val dayLabel = NoHourlyChecker.formatDayLabel(date)
                        noHourlyMessage = NoHourlyChecker.buildPendingMessage(dayLabel)
                        noHourlyFetching = true
                        onNeedHourlyRefresh(date) { newHourly ->
                            noHourlyMessage = if (NoHourlyChecker.hasHourlyForDay(newHourly, date, hourlySourceIds)) {
                                null
                            } else {
                                NoHourlyChecker.buildResultMessage(
                                    dayLabel,
                                    false,
                                    NoHourlyChecker.lastHourlyEndLabel(newHourly, hourlySourceIds),
                                )
                            }
                            noHourlyFetching = false
                        }
                    }
                    // Nothing can be fetched for this day (the source's whole horizon is stored).
                    val showNoHourlyData: (LocalDate, List<HourlyForecast>) -> Unit = { date, hourly ->
                        noHourlyFetching = false
                        noHourlyMessage = NoHourlyChecker.buildMessage(
                            NoHourlyChecker.formatDayLabel(date),
                            NoHourlyChecker.lastHourlyEndLabel(hourly, hourlySourceIds),
                        )
                    }

                    val isHourly = config.viewMode.isHourly
                    if (isHourly) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("hourly_temperature_surface")) {
                            // Shared scroll-zoom + drag-pan handlers for all three hourly graphs.
                            val handleZoomScroll: (Float, Int) -> Unit = { deltaZoom, cursorOffset ->
                                val newFactor = (config.zoomFactor + deltaZoom).coerceIn(0f, 1f)
                                if (newFactor != config.zoomFactor) {
                                    // Zooming in re-centers on the cursor; zooming out keeps the current center.
                                    val newOffset = if (deltaZoom < 0f) {
                                        cursorOffset.coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)
                                    } else {
                                        config.hourlyOffset
                                    }
                                    onUpdateConfig(config.copy(zoomFactor = newFactor, hourlyOffset = newOffset))
                                }
                            }
                            val handlePan: (Int) -> Unit = { deltaHours ->
                                val newOffset = (config.hourlyOffset + deltaHours).coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)
                                if (newOffset != config.hourlyOffset) {
                                    onUpdateConfig(config.copy(hourlyOffset = newOffset))
                                }
                            }
                            // Body-tap zoom toggle, shared by all three hourly graphs: advance one zoom
                            // stage, matching Android, and re-center on the tapped hour. The cycle is
                            // WIDE↔NARROW, or WIDE→NARROW→TWO_DAY→WIDE when the 2-day setting is on.
                            //
                            // The snap is deliberately ungated: the wheel can park the view at a
                            // multi-day span even with the setting off, and snapping that to TWO_DAY is
                            // what lets the next() below return it to WIDE in a single click.
                            val handleToggleZoom: (Int) -> Unit = { clickedOffset ->
                                val current = ZoomStage.nearestByTotalSpan(
                                    DesktopGraphUtils.totalSpanHoursFor(config.zoomFactor),
                                    config.settings.narrowZoomSpanHours,
                                )
                                val next = current.next(config.settings.multiDayZoomEnabled)
                                onUpdateConfig(
                                    config.copy(
                                        zoomFactor = DesktopGraphUtils.zoomFactorForStage(
                                            next,
                                            config.settings.narrowZoomSpanHours,
                                        ),
                                        hourlyOffset = clickedOffset.coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET),
                                    )
                                )
                            }
                            // ←/→ pan the hourly window by the same nav-jump the arrow buttons use.
                            SideEffect {
                                onRegisterArrowKeyHandler { left ->
                                    val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                    if (left && config.hourlyOffset > MIN_HOURLY_OFFSET) {
                                        handlePan(-jump); true
                                    } else if (!left && config.hourlyOffset < MAX_HOURLY_OFFSET) {
                                        handlePan(jump); true
                                    } else false
                                }
                            }
                            // Whenever zoom or pan changes, ask for deeper history if the left edge of the
                            // visible window now reaches further back than what's cached. The offset is
                            // negative when panned into the past, so subtracting it extends the reach.
                            // The view settling on a window by ‹ ›, a drag or reopening, not a day tap:
                            // any day it shows that Google has not covered is fetched, or a future day
                            // with no hourly says where data ends, once the view has rested
                            // PAN_SETTLE_MS (a drag ends in one fetch). Keyed on the days in view, not
                            // the hourly data, so a failed fetch is not retried on every cache reload
                            // (plans/261009-hourly-pan-into-empty-day-fetches.md).
                            val panCenterMs = System.currentTimeMillis() + config.hourlyOffset * 3_600_000L
                            val panStartMs = panCenterMs - DesktopGraphUtils.backHoursFor(config.zoomFactor) * 3_600_000L
                            val panEndMs = panCenterMs + DesktopGraphUtils.forwardHoursFor(config.zoomFactor) * 3_600_000L
                            val panFirstDay = java.time.Instant.ofEpochMilli(panStartMs).atZone(ZoneId.systemDefault()).toLocalDate()
                            val panLastDay = java.time.Instant.ofEpochMilli(panEndMs).atZone(ZoneId.systemDefault()).toLocalDate()
                            val latestHourly by rememberUpdatedState(snapshot.raw.hourly)
                            LaunchedEffect(panFirstDay, panLastDay, config.displaySource) {
                                kotlinx.coroutines.delay(HourlyOnDemand.PAN_SETTLE_MS)
                                if (noHourlyFetching) return@LaunchedEffect
                                val hourly = latestHourly
                                val action = HourlyOnDemand.panAction(
                                    config.displaySource,
                                    panStartMs,
                                    panEndMs,
                                    ZoneId.systemDefault(),
                                    System.currentTimeMillis(),
                                    hourly.filter { it.source == null || it.source == config.displaySource },
                                ) { day -> NoHourlyChecker.hasHourlyForDay(hourly, day, hourlySourceIds) }
                                when (action) {
                                    is HourlyOnDemand.PanAction.Fetch -> startHourlyFetch(action.date)
                                    is HourlyOnDemand.PanAction.NoDataMessage -> showNoHourlyData(action.date, hourly)
                                    HourlyOnDemand.PanAction.Nothing -> Unit
                                }
                            }
                            LaunchedEffect(config.zoomFactor, config.hourlyOffset) {
                                val earliestVisibleHoursBack =
                                    DesktopGraphUtils.backHoursFor(config.zoomFactor) - config.hourlyOffset
                                onNeedHistory(earliestVisibleHoursBack)
                            }
                            if (config.viewMode == ViewMode.CLOUD_COVER) {
                                CloudCoverGraph(
                                    hourly = snapshot.raw.hourly,
                                    priorDayCloudForecast = snapshot.priorDayCloudForecast,
                                    retroCloudActual = snapshot.retroCloudActual,
                                    priorDayBandForecast = snapshot.priorDayBandForecast,
                                    retroCloudBands = snapshot.retroCloudBands,
                                    displaySourceId = config.displaySource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                )
                            } else if (config.viewMode == ViewMode.PRECIPITATION) {
                                PrecipitationGraph(
                                    hourly = snapshot.raw.hourly,
                                    observations = snapshot.raw.rawObservations,
                                    displaySourceId = config.displaySource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                )
                            } else {
                                TemperatureGraph(
                                    hourly = snapshot.raw.hourly,
                                    currentTemp = snapshot.resolved.currentTemp,
                                    currentObservedAt = snapshot.resolved.currentObservedAt,
                                    observations = snapshot.raw.rawObservations,
                                    displaySourceId = config.displaySource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    personalStationWeight = config.personalStationWeight(),
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                    useCelsius = config.settings.useCelsius,
                                )
                            }
                            NavArrow(
                                alignment = Alignment.CenterStart,
                                enabled = config.hourlyOffset > MIN_HOURLY_OFFSET,
                                testTag = "hourly_nav_left",
                            ) {
                                val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                val newOffset = (config.hourlyOffset - jump).coerceAtLeast(MIN_HOURLY_OFFSET)
                                Log.d(TAG, "HourlyNav: left jump=${-jump}h zoom=${config.zoomFactor} offset ${config.hourlyOffset}->$newOffset")
                                onUpdateConfig(config.copy(hourlyOffset = newOffset))
                            }
                            NavArrow(
                                alignment = Alignment.CenterEnd,
                                enabled = config.hourlyOffset < MAX_HOURLY_OFFSET,
                                testTag = "hourly_nav_right",
                            ) {
                                val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                val newOffset = (config.hourlyOffset + jump).coerceAtMost(MAX_HOURLY_OFFSET)
                                Log.d(TAG, "HourlyNav: right jump=+${jump}h zoom=${config.zoomFactor} offset ${config.hourlyOffset}->$newOffset")
                                onUpdateConfig(config.copy(hourlyOffset = newOffset))
                            }
                            // Transient banner: a location-picker save's first fetch ("Getting weather
                            // for {place}…") or an on-demand deep-history pull, in flight or briefly on
                            // failure. Drawn last so it floats over the graph + arrows.
                            (noHourlyMessage ?: transientMessage)?.let { msg ->
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth(0.94f)
                                        .padding(top = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.72f),
                                ) {
                                    Text(
                                        text = msg,
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                            .then(if (msg == noHourlyMessage) Modifier.testTag("no_hourly_message") else Modifier),
                                        color = Color.White,
                                        fontSize = (12f * uiScale).sp,
                                    )
                                }
                            }

                            currentTempFetchError?.let { FetchFailureBanner(it, currentTempFetchPill, currentTempFetchIsWarmup, uiScale, onDismissCurrentTempError) }
                        }
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).testTag("daily_forecast_surface")) {
                            val dimensions = DesktopDailyForecastModel.dimensions(
                                widthDp = maxWidth.value.roundToInt(),
                                heightDp = maxHeight.value.roundToInt(),
                            )
                            val dailyState = DesktopDailyForecastModel.build(
                                config = config,
                                forecast = snapshot,
                                dimensions = dimensions,
                            )

                            // Report today's on-screen status up to the header: it decides whether
                            // to show the current-observations button and which date the
                            // forecast-history button opens. Read off the rendered days, the only
                            // source that accounts for clamping and zoom-out history columns.
                            val todayOnScreen = dailyState.days.any { it.isToday }
                            val observationsOnScreen =
                                dailyState.days.any { it.isToday || it.daysFromToday == -1 }
                            SideEffect {
                                dailyTodayInView = todayOnScreen
                                dailyObservationsInView = observationsOnScreen
                            }

                            // Sync both clamped values in one write so a simultaneous offset+zoom clamp
                            // doesn't clobber each other (two separate copy() calls off the same config would).
                            LaunchedEffect(dailyState.clampedDateOffset, dailyState.clampedExtraHistory) {
                                if (dailyState.clampedDateOffset != config.dateOffset ||
                                    dailyState.clampedExtraHistory != config.dailyExtraHistory) {
                                    onUpdateConfig(config.copy(
                                        dateOffset = dailyState.clampedDateOffset,
                                        dailyExtraHistory = dailyState.clampedExtraHistory,
                                    ))
                                }
                            }

                            // Snap-step horizontal drag → day offset; direction-gated by the same bounds
                            // the nav arrows use. A fast flick may emit >1 step; over-panning a column or
                            // two is harmless (model tolerates empty edge columns) and self-heals next gesture.
                            val handleDailyPan: (Int) -> Unit = { steps ->
                                val blocked = (steps < 0 && !dailyState.canNavigateLeft) ||
                                    (steps > 0 && !dailyState.canNavigateRight)
                                if (!blocked) {
                                    val target = dailyState.clampedDateOffset + steps
                                    if (target != config.dateOffset) onUpdateConfig(config.copy(dateOffset = target))
                                }
                            }
                            // Scroll-wheel zoom → extra history days; clamped to model-computed bounds.
                            val handleDailyZoom: (Int) -> Unit = { delta ->
                                val blocked = (delta > 0 && !dailyState.canZoomOut) ||
                                    (delta < 0 && !dailyState.canZoomIn)
                                if (!blocked) {
                                    val target = (dailyState.clampedExtraHistory + delta).coerceAtLeast(0)
                                    if (target != config.dailyExtraHistory) {
                                        onUpdateConfig(config.copy(dailyExtraHistory = target))
                                    }
                                }
                            }
                            val dailyInput = Modifier.dailyPanZoomInput(
                                columnCount = dailyState.days.size,
                                onPanDays = handleDailyPan,
                                onZoomScroll = handleDailyZoom,
                            )
                            // ←/→ step one day, gated by the same data bounds as the nav arrows.
                            SideEffect {
                                onRegisterArrowKeyHandler { left ->
                                    if (left && dailyState.canNavigateLeft) {
                                        handleDailyPan(-1); true
                                    } else if (!left && dailyState.canNavigateRight) {
                                        handleDailyPan(1); true
                                    } else false
                                }
                            }

                            val handleDayClick: (LocalDate, DayClickResolver.DayTapZone) -> Unit = { clickedDate, zone ->
                                val visibleSourceIds = config.effectiveSources.toSet()
                                val clickNow = LocalDateTime.now()
                                val clickedDay = dailyState.days.find { it.date == clickedDate }
                                val routingPrecip = dayClickRoutingPrecip(
                                    config, clickedDate, dailyState.days, clickNow, snapshot.raw.hourly,
                                )
                                val precipProb = routingPrecip.probability
                                val targetView = DayClickResolver.resolveView(zone, clickedDay?.iconName, precipProb)
                                val newOffset = DayClickResolver.calculateHourlyOffset(clickNow, clickedDate)
                                val clickSource = when (zone) {
                                    DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER -> "graph_day_upper"
                                    DayClickResolver.DayTapZone.MAIN_COLUMN -> "graph_day"
                                    DayClickResolver.DayTapZone.BOTTOM_ICON -> "graph_bottom_day"
                                }
                                onDayClickAudit(
                                    "date=$clickedDate zone=$zone targetView=$targetView offset=$newOffset " +
                                        "icon=${clickedDay?.iconName} precipGate=${routingPrecip.auditText()} " +
                                        "clickSource=$clickSource",
                                )
                                val nextConfig = dayClickConfig(
                                    config, clickedDate, dailyState.days, zone, clickNow,
                                    snapshot.raw.hourly,
                                )
                                val hasHourly = NoHourlyChecker.hasHourlyForDay(snapshot.raw.hourly, clickedDate, visibleSourceIds)
                                // Google keeps 72 h of hourly; a later day is fetched now (HourlyOnDemand).
                                val onDemandHours = HourlyOnDemand.hoursToCover(
                                    config.displaySource,
                                    clickedDate,
                                    ZoneId.systemDefault(),
                                    System.currentTimeMillis(),
                                    snapshot.raw.hourly.filter { it.source == null || it.source == config.displaySource },
                                )
                                // The day's hourly view opens either way, empty where data is missing.
                                onUpdateConfig(nextConfig)
                                when {
                                    onDemandHours != null -> startHourlyFetch(clickedDate)
                                    !hasHourly -> showNoHourlyData(clickedDate, snapshot.raw.hourly)
                                    else -> noHourlyMessage = null
                                }
                            }

                            if (dailyState.dimensions.useGraph) {
                                DailyForecastGraph(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize().then(dailyInput),
                                    scale = uiScale,
                                    onDayClick = handleDayClick,
                                    useCelsius = config.settings.useCelsius,
                                )
                            } else {
                                DailyForecastTextMode(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize().then(dailyInput),
                                    onDayClick = handleDayClick,
                                    useCelsius = config.settings.useCelsius,
                                )
                            }

                            NavArrow(
                                alignment = Alignment.CenterStart,
                                enabled = dailyState.canNavigateLeft,
                                testTag = "daily_nav_left",
                            ) {
                                onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset - 1))
                            }
                            NavArrow(
                                alignment = Alignment.CenterEnd,
                                enabled = dailyState.canNavigateRight,
                                testTag = "daily_nav_right",
                            ) {
                                onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset + 1))
                            }

                            noHourlyMessage?.let { msg ->
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = msg,
                                        textAlign = TextAlign.Center,
                                        color = Color.White.copy(alpha = 0.88f),
                                        fontSize = (13f * uiScale).sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.testTag("no_hourly_message"),
                                    )
                                }
                            }

                            // The daily view's banner is a quota notice only (whole source, or the
                            // daily forecast's own quota); see DesktopUiApplication.updateStatus.
                            currentTempFetchError?.let { FetchFailureBanner(it, currentTempFetchPill, currentTempFetchIsWarmup, uiScale, onDismissCurrentTempError) }
                        }
                    }
                }
            }
        }
      }
    }
}

/**
 * The fetch-failure banner over a graph: the widget's pill, laid out by the shared
 * [FailureBannerLayout] (wording, size, centring, stage) and only drawn here. Clicking it opens
 * [FailureDetailsCard] — [msg], whose first line is the title — as the widget's pill opens its error
 * page. The warm-up notice (post-wake offline grace window) has no pill and shows its card directly.
 */
@Composable
private fun BoxScope.FetchFailureBanner(
    msg: String,
    pill: DesktopFailurePill?,
    isWarmup: Boolean,
    uiScale: Float,
    onDismiss: () -> Unit,
) {
    // The pill shrinks at 8 s and fades at 24 s (FailureBannerStage, shared with the widget). Keyed
    // on the title so a repeat of the same failure stays quiet while a new one is shown in full.
    val bannerTitle = msg.substringBefore('\n')
    val bannerShownAtMs = remember(bannerTitle) { System.currentTimeMillis() }
    var bannerStage by remember(bannerTitle) { mutableStateOf(FailureBannerStage.FULL) }
    var showDetails by remember(bannerTitle) { mutableStateOf(false) }
    LaunchedEffect(bannerTitle, isWarmup) {
        bannerStage = FailureBannerStage.FULL
        if (isWarmup) return@LaunchedEffect
        for (boundaryMs in FailureBannerStage.STAGE_CHANGE_DELAYS_MS) {
            kotlinx.coroutines.delay(bannerShownAtMs + boundaryMs - System.currentTimeMillis())
            bannerStage = FailureBannerStage.at(System.currentTimeMillis() - bannerShownAtMs)
        }
    }
    if (isWarmup || pill == null || showDetails) {
        FailureDetailsCard(msg, isWarmup, uiScale, onClose = { showDetails = false }, onDismiss = onDismiss)
        return
    }
    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val pxPerDp = density.density * uiScale
        fun style(main: Boolean, sizePx: Float) = TextStyle(
            fontSize = with(density) { sizePx.toSp() },
            fontWeight = if (main) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = if (main) FailureBannerLayout.MAIN_LETTER_SPACING_EM.em else TextUnit.Unspecified,
        )
        fun measure(main: Boolean, text: String, sizePx: Float) =
            textMeasurer.measure(AnnotatedString(text), style(main, sizePx), softWrap = false, maxLines = 1)
        fun metrics(main: Boolean, sizePx: Float): Pair<Float, Float> {
            val m = measure(main, "Ag", sizePx)
            return -m.firstBaseline to (m.size.height - m.firstBaseline)
        }
        val layout = FailureBannerLayout.calculate(
            width = constraints.maxWidth.toFloat(),
            height = constraints.maxHeight.toFloat(),
            density = pxPerDp,
            sourceLabel = pill.sourceLabel,
            errorCode = pill.errorCode,
            failureTimeMs = pill.failureTimeMs,
            stage = bannerStage,
            measureMain = { text, size -> measure(true, text, size).size.width.toFloat() },
            measureDetail = { text, size -> measure(false, text, size).size.width.toFloat() },
            mainMetrics = { size -> metrics(true, size) },
            detailMetrics = { size -> metrics(false, size) },
        ) ?: return@BoxWithConstraints
        val b = layout.bounds
        Box(
            modifier = Modifier
                .offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
                .size(with(density) { (b.right - b.left).toDp() }, with(density) { (b.bottom - b.top).toDp() })
                .clickable { showDetails = true }
                .testTag("failure_pill")
                .drawBehind {
                    val radius = CornerRadius(layout.cornerRadius)
                    drawRoundRect(Color(FailureBannerLayout.BACKGROUND_ARGB), cornerRadius = radius, alpha = layout.alpha)
                    drawRoundRect(
                        Color(FailureBannerLayout.BORDER_ARGB),
                        cornerRadius = radius,
                        style = Stroke(width = FailureBannerLayout.BORDER_WIDTH_DP * pxPerDp),
                        alpha = layout.alpha,
                    )
                    fun line(main: Boolean, text: String, sizePx: Float, baselineY: Float, argb: Int) {
                        val m = measure(main, text, sizePx)
                        drawText(
                            m,
                            color = Color(argb),
                            topLeft = Offset(layout.centerX - b.left - m.size.width / 2f, baselineY - b.top - m.firstBaseline),
                            alpha = layout.alpha,
                        )
                    }
                    line(true, layout.mainText, layout.mainTextSize, layout.mainBaselineY, FailureBannerLayout.MAIN_TEXT_ARGB)
                    val detail = layout.detailText
                    val detailSize = layout.detailTextSize
                    val detailBaseline = layout.detailBaselineY
                    if (detail != null && detailSize != null && detailBaseline != null) {
                        line(false, detail, detailSize, detailBaseline, FailureBannerLayout.DETAIL_TEXT_ARGB)
                    }
                },
        )
    }
}

/**
 * The failure's full text: [msg]'s first line is the title, the rest the body. Clicking the card
 * closes it back to the pill; × dismisses the failure until a newer one. Warm-up renders
 * informational blue, a real failure red.
 */
@Composable
private fun BoxScope.FailureDetailsCard(
    msg: String,
    isWarmup: Boolean,
    uiScale: Float,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val surfaceColor = if (isWarmup) Color(0xFF1B2A3A) else Color(0xFF3E1C1C)
    val borderColor = if (isWarmup) Color(0xFF64B5F6) else Color(0xFFE57373)
    val titleColor = if (isWarmup) Color(0xFFBBDEFB) else Color(0xFFFFCDD2)
    val bodyColor = if (isWarmup) Color(0xFF90CAF9) else Color(0xFFEF9A9A)
    Surface(
        modifier = Modifier.align(Alignment.Center).clickable(enabled = !isWarmup) { onClose() },
        shape = RoundedCornerShape(12.dp),
        color = surfaceColor.copy(alpha = 0.95f),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val lines = msg.split("\n")
                if (lines.isNotEmpty()) {
                    Text(
                        text = lines[0],
                        color = titleColor,
                        fontSize = (14f * uiScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                    lines.drop(1).forEach { line ->
                        Text(
                            text = line,
                            color = bodyColor,
                            fontSize = (12f * uiScale).sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size((24f * uiScale).dp)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "×",
                    color = bodyColor,
                    fontSize = (18f * uiScale).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun NavArrow(
    alignment: Alignment,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.width(28.dp).fillMaxHeight().testTag(testTag),
        ) {
            Icon(
                imageVector = if (alignment == Alignment.CenterStart) {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 0.75f else 0.18f),
            )
        }
    }
}




@Composable
private fun DailyForecastTextMode(
    state: DesktopDailyViewState,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate, DayClickResolver.DayTapZone) -> Unit = { _, _ -> },
    useCelsius: Boolean,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        state.days.forEach { day ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("day_tab_${day.date}")
                    .clickable {
                        onDayClick(day.date, DayClickResolver.DayTapZone.MAIN_COLUMN)
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = (MaterialTheme.typography.labelSmall.fontSize.value * 1.3f).sp),
                    color = if (day.isToday) Color.Yellow else Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                )
                val high = listOfNotNull(day.solidHigh, day.forecastHigh, day.snapshotHigh).maxOrNull()
                val low = listOfNotNull(day.solidLow, day.forecastLow, day.snapshotLow).minOrNull()
                Text(
                    text = com.weatherwidget.shared.util.TempUtils.formatTemp(high, useCelsius) ?: "--",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = (MaterialTheme.typography.titleMedium.fontSize.value * 1.3f).sp),
                    color = Color.White,
                    maxLines = 1,
                )
                if (state.dimensions.cols >= 2) {
                    Text(
                        text = com.weatherwidget.shared.util.TempUtils.formatTemp(low, useCelsius) ?: "--",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (MaterialTheme.typography.labelSmall.fontSize.value * 1.3f).sp),
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}



@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
