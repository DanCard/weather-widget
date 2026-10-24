package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.shared.util.PreferredSourceHome
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.SunInfo
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime
import java.time.LocalDate

/**
 * Header resolve-and-bind logic for the daily view, extracted from `DailyViewHandler`.
 *
 * Lives in its own file so churn in the rendering/binding side (header scale, precip
 * placement, disclosure) does not perturb the larger `DailyViewHandler.updateWidget`
 * orchestrator. The data shape ([DailyViewHandler.HeaderState]) stays on
 * `DailyViewHandler` so callers across the package (notably `DailyGraphRenderer`) keep
 * one stable reference type for the per-render snapshot.
 */
internal object DailyHeaderResolver {
    private const val TAG = "DailyHeaderResolver"

    // Header constants that only the header path needs; separated from the
    // graph-vs-text mode-selection constants which stay in DailyViewHandler.
    internal const val HEADER_ICON_TINT = 0xAAFFFFFF.toInt()

    internal data class HeaderResolution(
        val state: DailyViewHandler.HeaderState,
        val precipPlacement: DailyHeaderBinder.HeaderPrecipPlacement,
    )

    internal fun resolveAndBind(
        context: Context,
        views: RemoteViews,
        displaySource: WeatherSource,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        weatherByDate: Map<LocalDate, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        dimensions: WidgetDimensions,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        sunInfo: SunInfo,
        deltaFromYesterday: Float?,
        dateOffset: Int,
        skipYesterday: Boolean,
    ): HeaderResolution {
        val resolution = resolveState(
            context = context,
            displaySource = displaySource,
            now = now,
            lat = lat,
            lon = lon,
            weatherByDate = weatherByDate,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            dimensions = dimensions,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            numColumns = numColumns,
            numRows = numRows,
            useGraph = useGraph,
            sunInfo = sunInfo,
            deltaFromYesterday = deltaFromYesterday,
            dateOffset = dateOffset,
            skipYesterday = skipYesterday,
        )
        bind(
            context = context,
            views = views,
            state = resolution.state,
            precipPlacement = resolution.precipPlacement,
            useGraph = useGraph,
            isIconWidth = dimensions.isIconWidth,
        )
        return resolution
    }

    @Suppress("LongParameterList")
    internal fun resolveState(
        context: Context,
        displaySource: WeatherSource,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        weatherByDate: Map<LocalDate, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        dimensions: WidgetDimensions,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        sunInfo: SunInfo,
        deltaFromYesterday: Float?,
        dateOffset: Int,
        skipYesterday: Boolean,
    ): HeaderResolution {
        val today = now.toLocalDate()

        val (currentTempResolution, resolveMs) =
            CurrentTempResolutionHelper.resolveTimed(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts.ifEmpty { hourlyForecasts },
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                )
        val currentTemp = currentTempResolution.displayTemp

        // Icon, temp, rain % and delta: the shared header rule (desktop draws the same content).
        val todayWeather = weatherByDate[today]
        val content = HeaderContent.build(
            view = HeaderContent.View.DAILY,
            hourly = hourlyForecasts.map(HourlyForecastEntity::toHourlyForecast),
            displaySourceId = displaySource.id,
            dailyFallback = todayWeather?.headerDailyFallback(),
            fallbackDailyPrecip = todayWeather?.precipProbability,
            currentTemp = currentTemp,
            isStaleEstimate = currentTempResolution.isStaleEstimate,
            numColumns = numColumns,
            deltaFromYesterday = deltaFromYesterday,
            now = now,
            centerTime = now,
            lat = lat,
            lon = lon,
            useCelsius = stateManager.useCelsius(),
            widthDp = dimensions.widthDp,
        )
        val iconRes = WeatherIconMapper.fromIconName(content.iconName ?: WeatherConditionResolver.IC_UNKNOWN)
        val formattedTemp = content.tempText
        val precipProb = content.computedPrecipProbability
        val isPrecipVisible = content.precipProbability != null
        val precipTextSizeDp = content.precipFontScale?.let { HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * it }
        val headerPrecipText = content.precipText

        // Forecast delta (observed − forecast): kept on the state for the ghost line and the
        // today-column overlay. The HEADER shows the yesterday delta instead.
        val delta = currentTempResolution.appliedDelta
        val deltaText = content.deltaText
        val deltaVisible = deltaText != null

        // Pick API label
        val apiSourceText = content.sourceLabel
        val apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows)
        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            currentTempText = formattedTemp,
            deltaText = deltaText,
            precipText = headerPrecipText,
            precipTextSizeDp = precipTextSizeDp,
            currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
        )

        // Daily header buttons. `observationsInView` uses the shared visible-date-range helper rather
        // than `dateOffset == 0`: the daily view shows yesterday alongside today, so a non-zero offset
        // can still have today on screen, and an offset test would wrongly drop the observations
        // button there. Same primitives the renderer's day window is built from. The button stays for
        // yesterday too — its station-history affordance is date-independent.
        val (visibleFrom, visibleTo) =
            NavigationUtils.getVisibleDateRange(today, dateOffset, numColumns, skipYesterday)
        val observationsInView = NavigationUtils.isTodayOrYesterdayInRange(today, visibleFrom, visibleTo)
        // The home button: back to the preferred source when another is shown, else — on the
        // preferred source with today panned off screen — back to yesterday + today.
        val todayInView = !today.isBefore(visibleFrom) && !today.isAfter(visibleTo)
        val homeAction = PreferredSourceHome.homeAction(
            currentSourceId = displaySource.id,
            visibleSourceIds = stateManager.getVisibleSourcesOrder().map { it.id },
            todayInView = todayInView,
        )

        // What the header shows: the shared drop order (temp > rain % > delta > buttons > weather
        // icon > date > "from yest"). The date and weather icon are painted in graph mode only.
        val deltaLabel = context.getString(R.string.header_delta_from_yesterday)
        // Graph mode only and when enabled in settings (disabled by default) for months.
        val buttons = HeaderButtons.daily(
            observationsInView = observationsInView,
            home = homeAction != null,
            months = useGraph && stateManager.showDailyZoomOutButton(),
        )
        val measured = HeaderWidthChecker.measureHeader(
            context = context,
            widthDp = dimensions.widthDp,
            headerScale = headerScale,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            currentTempText = formattedTemp,
            currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = deltaText,
            deltaLabelText = deltaLabel,
            precipText = headerPrecipText,
            precipTextSizeDp = precipTextSizeDp,
            dateText = if (useGraph && numColumns >= HeaderConstants.DATE_MIN_COLUMNS) {
                today.format(DailyViewHandler.headerDateFormatter())
            } else null,
            includeIcon = useGraph,
            baseButtons = if (dimensions.isIconWidth) 0 else buttons.baseButtons,
            wantHome = buttons.wantHome,
            wantMonths = buttons.wantMonths,
        )
        val fit = HeaderFit.resolve(measured)
        Log.d(TAG, "HEADER_FIT view=daily widget=$appWidgetId widthDp=${dimensions.widthDp} scale=$headerScale $fit $measured")

        val headerPrecipPlacement = DailyHeaderBinder.resolveHeaderPrecipPlacement(
            precipText = headerPrecipText,
            precipTextSizeDp = precipTextSizeDp,
            headerCanShowPrecip = fit.showRain,
        )
        val deltaLabelText = deltaLabel.takeIf { fit.showCaption }

        val headerState = DailyViewHandler.HeaderState(
            iconRes = iconRes,
            currentTemp = currentTemp,
            formattedTemp = formattedTemp,
            estimatedTemp = currentTempResolution.estimatedTemp,
            observedTemp = currentTempResolution.observedTemp,
            appliedDelta = delta,
            yesterdayDelta = deltaFromYesterday,
            deltaVisible = deltaVisible,
            deltaText = deltaText,
            deltaLabelText = deltaLabelText,
            precipProb = precipProb,
            isPrecipVisible = isPrecipVisible,
            precipTextSizeDp = precipTextSizeDp,
            precipText = headerPrecipText,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            fit = fit,
            measured = measured,
            headerScale = headerScale,
            resolveMs = resolveMs,
            observationsInView = observationsInView,
            homeAction = if (fit.homeShown) homeAction else null,
        )
        return HeaderResolution(headerState, headerPrecipPlacement)
    }

    internal fun bind(
        context: Context,
        views: RemoteViews,
        state: DailyViewHandler.HeaderState,
        precipPlacement: DailyHeaderBinder.HeaderPrecipPlacement,
        useGraph: Boolean,
        isIconWidth: Boolean,
    ) {
        // Set initial API source indicator (overwritten later once dual-source fit is decided)
        views.setTextViewText(R.id.api_source, state.apiSourceText)
        views.setTextViewText(R.id.text_mode_api_source, state.apiSourceText)

        if (useGraph) {
            views.setImageViewResource(R.id.weather_icon, state.iconRes)
            views.setViewVisibility(R.id.weather_icon, View.VISIBLE)
            views.setViewVisibility(R.id.current_weather_container, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.weather_icon, View.GONE)
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)

        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, state.isPrecipVisible)

        // Bind header elements with proper scale
        HeaderRemoteViewsBinder.bindApiSource(
            context = context,
            views = views,
            sourceText = state.apiSourceText,
            textSizeDp = state.apiTextSizeDp,
            scale = state.headerScale,
        )

        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.weather_icon,
            iconRes = state.iconRes,
            sizeDp = HeaderConstants.WEATHER_ICON_SIZE_DP,
            scale = state.headerScale,
        )
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.settings_icon,
            iconRes = if (isIconWidth) 0 else R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = state.headerScale,
            tintColor = HEADER_ICON_TINT
        )
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.text_mode_settings_icon,
            iconRes = if (isIconWidth) 0 else R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = state.headerScale,
            tintColor = HEADER_ICON_TINT
        )
        HeaderRemoteViewsBinder.bindCurrentTemp(
            context = context,
            views = views,
            formattedTemp = state.formattedTemp,
            textSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            hideDeltaOnNull = true,
            scale = state.headerScale
        )
        HeaderRemoteViewsBinder.bindPrecipProbability(
            context = context,
            views = views,
            precipText = if (state.isPrecipVisible) state.precipText ?: "${state.precipProb ?: 0}%" else null,
            textSizeDp = state.precipTextSizeDp ?: 0f,
            scale = state.headerScale,
        )
        HeaderRemoteViewsBinder.bindDelta(
            context = context,
            views = views,
            deltaText = state.deltaText,
            deltaVisible = state.deltaVisible,
            scale = state.headerScale,
        )
        HeaderRemoteViewsBinder.bindDeltaLabel(
            context = context,
            views = views,
            labelText = state.deltaLabelText,
            labelVisible = state.deltaVisible && state.deltaLabelText != null,
            scale = state.headerScale,
        )

        if (useGraph) {
            HeaderRemoteViewsBinder.applyFit(
                views,
                state.fit,
                isDeltaVisible = state.deltaVisible,
                isPrecipVisible = state.isPrecipVisible,
            )
        }
    }
}

/** Today's daily row as the header icon's no-hourly fallback (shared [HeaderContent]). */
internal fun ForecastEntity.headerDailyFallback(): HeaderContent.DailyFallback =
    HeaderContent.DailyFallback(
        sourceId = source,
        nativeToken = nativeDailyIconToken,
        condition = condition,
        precipProbability = precipProbability,
    )
