package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunInfo
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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
    internal const val GRAPH_CONTENT_PADDING_DP = 24

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
        smoothedForecasts: Map<Long, Float>?,
        sunInfo: SunInfo,
        headerDateFormatter: DateTimeFormatter,
        deltaFromYesterday: Float?,
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
            smoothedForecasts = smoothedForecasts,
            sunInfo = sunInfo,
            headerDateFormatter = headerDateFormatter,
            deltaFromYesterday = deltaFromYesterday,
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
        smoothedForecasts: Map<Long, Float>?,
        sunInfo: SunInfo,
        headerDateFormatter: DateTimeFormatter,
        deltaFromYesterday: Float?,
    ): HeaderResolution {
        val today = now.toLocalDate()

        val todayHeaderForecast = DailyHeaderBinder.resolveTodayHeaderForecast(
            now = now,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
        )
        val iconRes =
            if (todayHeaderForecast != null) {
                WeatherIconMapper.getIconResource(
                    condition = todayHeaderForecast.condition,
                    isNight = sunInfo.isNight,
                    cloudCover = todayHeaderForecast.cloudCover,
                    precipProbability = todayHeaderForecast.precipProbability,
                )
            } else {
                DailyForecastIconResolver.resolveIcon(
                    weather = weatherByDate[today],
                    targetDate = today,
                    now = now,
                    latitude = lat,
                    longitude = lon,
                )
            }

        val (currentTempResolution, resolveMs) =
            CurrentTempResolutionHelper.resolveAndPersistDelta(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts.ifEmpty { hourlyForecasts },
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                lat = lat,
                lon = lon,
                smoothedForecasts = smoothedForecasts,
            )
        val currentTemp = currentTempResolution.displayTemp

        val formattedTemp =
            currentTemp?.let {
                CurrentTemperatureResolver.formatDisplayTemperature(
                    temp = it,
                    numColumns = numColumns,
                    isStaleEstimate = currentTempResolution.isStaleEstimate,
                    useCelsius = stateManager.useCelsius(),
                )
            }

        val todayWeather = weatherByDate[today]
        val precipProb =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = todayWeather?.precipProbability,
                referenceTime = now,
            )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(precipProb)
        val isNightPrecip = precipProb != null && HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            referenceTime = now,
            sunriseHour = sunInfo.sunTimes.sunriseHour,
            sunsetHour = sunInfo.sunTimes.sunsetHour,
        )
        val precipTextSizeDp = if (precipProb != null) {
            HeaderPrecipCalculator.getPrecipTextSize(precipProb) *
                if (isNightPrecip) HeaderPrecipCalculator.NIGHT_SCALE else 1f
        } else null

        // Forecast delta (observed − forecast): kept on the state for the ghost line and the
        // today-column overlay. The HEADER shows the yesterday delta instead (always visible when
        // it exists and clears the noise threshold — no graph-window gate).
        val delta = currentTempResolution.appliedDelta
        val deltaVisible =
            currentTemp != null &&
            deltaFromYesterday != null &&
            abs(deltaFromYesterday) >= DailyHeaderBinder.DELTA_VISIBILITY_THRESHOLD
        // Compute the unit-corrected, display-formatted delta string here alongside
        // formattedTemp (which also uses stateManager.useCelsius() at the same point in
        // time) so resolve and bind share one unit snapshot. Avoids re-instantiating
        // WidgetStateManager (SharedPreferences hit) inside the per-render bind path.
        val deltaText = if (deltaVisible && deltaFromYesterday != null) {
            val displayDelta = if (stateManager.useCelsius()) deltaFromYesterday / 1.8f else deltaFromYesterday
            String.format("%+.1f", displayDelta)
        } else null

        // Pick API label
        val apiSourceText = displaySource.shortDisplayName
        val apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows)
        val deltaTextForFit = if (deltaVisible) String.format("%+.1f", deltaFromYesterday) else null
        val precipTextForFit = if (isPrecipVisible) "${precipProb}%" else null

        val disclosure = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            currentTempText = formattedTemp,
            deltaText = deltaTextForFit,
            precipText = precipTextForFit,
            precipTextSizeDp = precipTextSizeDp,
            currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
        )

        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            currentTempText = formattedTemp,
            deltaText = deltaTextForFit,
            precipText = precipTextForFit,
            precipTextSizeDp = precipTextSizeDp,
            currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
        )

        val widthDpForPrecip = dimensions.widthDp - GRAPH_CONTENT_PADDING_DP
        val dateText = if (numColumns >= HeaderConstants.DATE_MIN_COLUMNS) today.format(headerDateFormatter) else null
        val headerPrecipPlacement = DailyHeaderBinder.resolveHeaderPrecipPlacement(
            context = context,
            widthDp = widthDpForPrecip,
            numColumns = numColumns,
            currentTempText = formattedTemp,
            deltaText = if (deltaVisible && disclosure.showsDelta()) String.format("%+.1f", deltaFromYesterday) else null,
            precipText = if (isPrecipVisible) "$precipProb%" else null,
            precipTextSizeDp = precipTextSizeDp,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            dateText = dateText,
            headerCanShowPrecip = disclosure.showsPrecip(),
            includeIcon = disclosure.showsIcon(),
        )

        // "from yest" caption after the delta: opportunistic — only when the delta itself is
        // shown and the whole left cluster (caption included) still clears the API label.
        val deltaLabelText = if (deltaVisible && disclosure.showsDelta()) {
            val label = context.getString(R.string.header_delta_from_yesterday)
            val fits = HeaderWidthChecker.deltaLabelFitsInHeader(
                context = context,
                widthDp = dimensions.widthDp,
                apiSourceText = apiSourceText,
                apiTextSizeDp = apiTextSizeDp,
                currentTempText = formattedTemp,
                deltaText = deltaTextForFit,
                deltaLabelText = label,
                precipText = if (isPrecipVisible && disclosure.showsPrecip()) precipTextForFit else null,
                precipTextSizeDp = precipTextSizeDp,
                includeIcon = disclosure.showsIcon(),
                currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
            )
            if (fits) label else null
        } else null

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
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            disclosure = disclosure,
            headerScale = headerScale,
            resolveMs = resolveMs,
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
            precipText = if (state.isPrecipVisible) "${state.precipProb ?: 0}%" else null,
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

        if (useGraph && state.disclosure != HeaderDisclosureLevel.NONE) {
            HeaderRemoteViewsBinder.applyDisclosure(
                views,
                state.disclosure,
                isDeltaVisible = state.deltaVisible,
                isPrecipVisible = state.isPrecipVisible,
                isDeltaLabelVisible = state.deltaLabelText != null,
            )
        } else if (useGraph) {
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }
    }
}
