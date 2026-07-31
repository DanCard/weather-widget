package com.weatherwidget.widget.handlers

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.DailyForecastGraphRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object DailyGraphRenderer {
    private const val TAG = "DailyGraphRenderer"

    internal data class RenderMetrics(
        val prepareMs: Long,
        val renderMs: Long,
    )

    internal suspend fun render(
        ctx: DailyViewHandler.DailyRenderContext,
        headerState: DailyViewHandler.HeaderState,
        headerPrecipPlacement: DailyHeaderBinder.HeaderPrecipPlacement,
        dimensions: WidgetDimensions,
        startupToken: String?,
        resolveMs: Long,
        lat: Double,
        lon: Double,
    ): RenderMetrics {
        val todayStr = ctx.today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val isIconWidth = dimensions.isIconWidth
        val formattedTemp = headerState.formattedTemp
        val iconRes = headerState.iconRes
        val deltaVisible = headerState.deltaVisible
        val delta = headerState.appliedDelta
        val isPrecipVisible = headerState.isPrecipVisible
        val apiSourceText = headerState.apiSourceText
        val apiTextSizeDp = HeaderConstants.apiTextSizeDp(ctx.numRows)
        val disclosure = headerState.disclosure
        val headerScale = headerState.headerScale

        DailyVisibilityManager.setGraphModeViews(ctx.views)

        val prepareStartMs = SystemClock.elapsedRealtime()
        val todayActual = ctx.dailyActuals[ctx.today]
        val sourceCurrentTemps = ctx.currentTemps.filter {
            it.api == ctx.displaySource.id ||
                it.api == WeatherSource.GENERIC_GAP.id
        }
        val currentTempSpan =
            if (sourceCurrentTemps.isEmpty()) {
                "none"
            } else {
                val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                val firstTs = sourceCurrentTemps.minOf { it.timestamp }
                val lastTs = sourceCurrentTemps.maxOf { it.timestamp }
                val firstLocal = Instant.ofEpochMilli(firstTs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(formatter)
                val lastLocal = Instant.ofEpochMilli(lastTs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(formatter)
                "$firstLocal..$lastLocal"
            }
        Log.d(
            TAG,
            "dailyTodayInputs: widget=${ctx.appWidgetId} source=${ctx.displaySource.id} date=${ctx.today} " +
                "dailyActual.high=${todayActual?.highTemp} dailyActual.low=${todayActual?.lowTemp} " +
                "currentTempResolution=${ctx.currentTemp} observedAt=${ctx.observedAt} " +
                "sourceCurrentRows=${sourceCurrentTemps.size} sourceCurrentSpan=$currentTempSpan " +
                "hourlyRows=${ctx.hourlyForecasts.count { it.source == ctx.displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }}",
        )

        val preparedDays = DailyViewLogic.prepareGraphDayInputs(
            ctx.now, ctx.centerDate, ctx.today, ctx.weatherByDate, ctx.forecastSnapshots,
            ctx.numColumns, ctx.displaySource, ctx.skipYesterday, ctx.skipHistory,
            ctx.hourlyForecasts, ctx.stateManager, ctx.appWidgetId, ctx.precipProb,
            ctx.dailyActuals, ctx.climateNormals, ctx.currentTemps,
            currentTemp = ctx.currentTemp,
            observedAt = ctx.observedAt,
            allowTodayRainChanceLabel = true,
            todayLabel = ctx.context.getString(R.string.today),
        )
        val days = preparedDays.map(DailyViewLogic.PreparedGraphDay::renderDay)
        val prepareMs = SystemClock.elapsedRealtime() - prepareStartMs

        days.find { it.isToday }?.let { todayDay ->
            ctx.appLogDao.log(
                DailyViewHandler.LOG_TAG_TODAY_BAR_DEBUG,
                "widget=${ctx.appWidgetId} mode=GRAPH obsHigh=${todayDay.solidLineHigh} obsLow=${todayDay.solidLineLow} " +
                    "fHigh=${todayDay.dashedLineHigh} fLow=${todayDay.dashedLineLow} " +
                    "trueHigh=${todayDay.ghostLineHigh} bStackLow=${todayDay.bottomStackLow} " +
                    "sHigh=${todayDay.snapshotHigh} sLow=${todayDay.snapshotLow} " +
                    "fallback=${todayDay.isTodayForecastFallback}",
                "DEBUG"
            )
            withContext(Dispatchers.IO) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    val todaySourceObservations = loadTodaySourceObservations(
                        database = ctx.database,
                        today = ctx.today,
                        lat = lat,
                        lon = lon,
                        displaySource = ctx.displaySource,
                    )
                    ctx.appLogDao.log(
                        DailyViewHandler.LOG_TAG_TODAY_HIGH_PROVENANCE,
                        buildTodayHighProvenanceMessage(
                            appWidgetId = ctx.appWidgetId,
                            today = ctx.today,
                            displaySource = ctx.displaySource,
                            forecastWeather = ctx.weatherByDate[ctx.today],
                            dailyActual = todayActual,
                            todayDay = todayDay,
                            currentTemp = ctx.currentTemp,
                            observedAt = ctx.observedAt,
                            observations = todaySourceObservations,
                        ),
                        "DEBUG",
                    )
                }
            }
        }

        // prepareGraphDays always returns exactly numColumns days, and numColumns is derived from
        // the widget width — identical at every nav offset. Cap defensively against the *live*
        // column count so a navigated view shows the same number of days as the offset-0 ("today")
        // view. This previously capped against a count stored at offset 0, which went stale and
        // trimmed the view whenever the column count grew (e.g. a wider widget gaining a day):
        // a navigated widget stayed pinned to the old count until it returned to today.
        val displayDays = if (days.size > ctx.numColumns) days.take(ctx.numColumns) else days
        Log.d(TAG, "render: Graph mode - prepared ${days.size} days, displaying ${displayDays.size} for ${ctx.numColumns} columns (offset=${ctx.dateOffset}).")

        if (displayDays.isEmpty()) {
            return RenderMetrics(prepareMs, 0L)
        }

        val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(ctx.context, dimensions.widthDp, dimensions.heightDp)
        val dateText = if (displayDays.size >= HeaderConstants.DATE_MIN_COLUMNS) ctx.today.format(DailyViewHandler.headerDateFormatter()) else null

        val graphRefreshDecisions = computeMissingDataRefreshes(
            today = ctx.today,
            displaySource = ctx.displaySource,
            dailyActuals = ctx.dailyActuals,
            visibleDates = displayDays.map { it.date }.toSet(),
            todayHasSnapshot = displayDays.find { it.isToday }
                ?.let { it.snapshotHigh != null || it.snapshotLow != null } ?: false,
            todayHasForecast = displayDays.find { it.isToday }
                ?.let { it.dashedLineHigh != null && it.dashedLineLow != null } ?: false,
        )
        for (decision in graphRefreshDecisions) {
            DailyViewHandler.requestMissingDataRefresh(
                context = ctx.context,
                appLogDao = ctx.appLogDao,
                stateManager = ctx.stateManager,
                appWidgetId = ctx.appWidgetId,
                displaySource = ctx.displaySource,
                refreshType = decision.refreshType,
                cooldownMs = decision.cooldownMs,
                logTag = decision.logTag,
                forceRefresh = decision.forceRefresh,
                reason = decision.reason,
                message = "widget=${ctx.appWidgetId} source=${ctx.displaySource.id} ${decision.refreshType} refresh, enqueueing worker",
            )
        }

        val displayedMetadata =
            preparedDays
                .take(displayDays.size)
                .associateBy { it.renderDay.date }
        if (displayDays.any { day ->
                day.isToday && displayedMetadata[day.date]?.rainSummary != null
            }) {
            ctx.stateManager.markRainShown(ctx.appWidgetId, todayStr)
        }

        DailyViewHandler.logDailyRenderSummary(
            appLogDao = ctx.appLogDao,
            appWidgetId = ctx.appWidgetId,
            dateOffset = ctx.dateOffset,
            displaySource = ctx.displaySource,
            numColumns = ctx.numColumns,
            numRows = ctx.numRows,
            useGraph = true,
            skipYesterday = ctx.skipYesterday,
            centerDate = ctx.centerDate,
            visibleDates = displayDays.map { it.date },
            cloudDays = displayDays,
            hourlyForecasts = ctx.hourlyForecasts,
        )
        logGraphDayIconDetails(
            context = ctx.context,
            appWidgetId = ctx.appWidgetId,
            displayDays = displayDays,
            metadataByDate = displayedMetadata,
        )

        val isNightPrecip = ctx.precipProb != null && HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = ctx.hourlyForecasts,
            displaySource = ctx.displaySource,
            referenceTime = ctx.now,
            sunriseHour = ctx.sunInfo.sunTimes.sunriseHour,
            sunsetHour = ctx.sunInfo.sunTimes.sunsetHour,
        )
        val headerRenderData = if (disclosure != HeaderDisclosureLevel.NONE) {
            DailyForecastGraphRenderer.HeaderRenderData(
                iconRes = iconRes,
                currentTempText = formattedTemp,
                deltaText = if (deltaVisible) {
                    val displayDelta = delta?.let { if (ctx.stateManager.useCelsius()) it / 1.8f else it }
                    displayDelta?.let { String.format("%+.1f", it) }
                } else null,
                precipText = if (isPrecipVisible) "${ctx.precipProb}%" else null,
                precipTextSizeDp = if (isPrecipVisible) {
                    HeaderPrecipCalculator.getPrecipTextSize(ctx.precipProb ?: 0) *
                        if (isNightPrecip) HeaderPrecipCalculator.NIGHT_SCALE else 1f
                } else HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
                dateText = dateText,
                apiSourceText = if (isIconWidth) null else apiSourceText,
                apiTextSizeDp = apiTextSizeDp,
                settingsIconRes = if (isIconWidth) 0 else R.drawable.ic_settings_gear,
                showIcon = disclosure.showsIcon(),
                showDelta = deltaVisible && disclosure.showsDelta(),
                showPrecip = isPrecipVisible && headerPrecipPlacement.showHeaderPrecip,
                headerScale = headerScale,
            )
        } else null

        val renderStartMs = SystemClock.elapsedRealtime()
        val renderResult = DailyForecastGraphRenderer.renderGraph(
            ctx.context,
            displayDays,
            bitmapDims.widthPx,
            bitmapDims.heightPx,
            bitmapDims.bitmapScale,
            displayDays.size,
            job = coroutineContext[Job],
            headerData = headerRenderData,
            showErrorWatermark = ctx.stateManager.isSourceErrored(ctx.displaySource),
            errorSourceLabel = ctx.displaySource.displayName,
            errorCode = ctx.stateManager.getSourceLastErrorCode(ctx.displaySource),
            errorFailureTimeMs = ctx.stateManager.getSourceLastFailureTime(ctx.displaySource),
            useCelsius = ctx.stateManager.useCelsius(),
        )
        val bitmap = renderResult.bitmap
        val nightRainLabelDraws =
            renderResult.rainLabelPlacements.filter {
                it.kind == DailyForecastGraphRenderer.RainLabelKind.NIGHT
            }
        val renderMs = SystemClock.elapsedRealtime() - renderStartMs
        ctx.views.setImageViewBitmap(R.id.graph_view, bitmap)

        ctx.views.setViewVisibility(R.id.current_temp, View.INVISIBLE)
        ctx.views.setViewVisibility(R.id.current_temp_delta, View.INVISIBLE)
        ctx.views.setViewVisibility(R.id.precip_probability, View.INVISIBLE)
        ctx.views.setViewVisibility(R.id.weather_icon, View.INVISIBLE)
        ctx.views.setViewVisibility(R.id.api_source, View.INVISIBLE)
        ctx.views.setViewVisibility(R.id.settings_icon, View.INVISIBLE)
        ctx.views.setViewVisibility(R.id.header_date_center, View.GONE)
        ctx.views.setViewVisibility(R.id.header_date_right, View.GONE)

        DailyClickHandlerFactory.setupGraphDayClickHandlers(ctx.context, ctx.views, ctx.appWidgetId, ctx.now, displayDays, lat, lon, ctx.displaySource, displayDays.size)
        DailyClickHandlerFactory.setupGraphBottomDayClickHandlers(ctx.context, ctx.views, ctx.appWidgetId, ctx.now, displayDays, lat, lon, ctx.displaySource, displayDays.size)
        NightRainGridMapper.setupNightRainClickHandlers(
            context = ctx.context,
            views = ctx.views,
            appWidgetId = ctx.appWidgetId,
            now = ctx.now,
            days = displayDays,
            lat = lat,
            lon = lon,
            displaySource = ctx.displaySource,
            bitmapWidthPx = bitmapDims.widthPx,
            bitmapHeightPx = bitmapDims.heightPx,
            nightLabelDraws = nightRainLabelDraws,
            buildClickIntent = { aid, di, d, ir, la, lo, ds, n, tmo, oo, cs ->
                DailyClickHandlerFactory.buildDayClickIntent(ctx.context, aid, di, d, ir, la, lo, ds, n, tmo, oo, cs)
            },
        )

        return RenderMetrics(prepareMs, renderMs)
    }

    private fun logGraphDayIconDetails(
        context: Context,
        appWidgetId: Int,
        displayDays: List<DailyForecastGraphRenderer.DayData>,
        metadataByDate: Map<LocalDate, DailyViewLogic.PreparedGraphDay>,
    ) {
        displayDays.forEachIndexed { index, day ->
            // columnIndex is always set by DailyViewLogic; fallback to list position for safety
            val colIndex = day.columnIndex ?: index
            val iconRes = day.iconRes
            val iconName =
                iconRes?.let {
                    runCatching { context.resources.getResourceEntryName(it) }.getOrNull()
                } ?: "null"
            Log.d(
                TAG,
                "graphDay widget=$appWidgetId col=${colIndex + 1} date=${day.date} " +
                    "isToday=${day.isToday} iconRes=$iconRes iconName=$iconName " +
                    "isRainy=${iconRes?.let(WeatherIconMapper::isPrecipitation) ?: false} " +
                    "isCloudEligible=${iconRes?.let(WeatherIconMapper::isCloudForecastEligible) ?: false} " +
                    "hasRainForecast=${metadataByDate[day.date]?.hasRainForecast ?: false}",
            )
        }
    }

    private suspend fun loadTodaySourceObservations(
        database: WeatherDatabase,
        today: LocalDate,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
    ): List<ObservationEntity> {
        val zoneId = ZoneId.systemDefault()
        val startMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return database.observationDao()
            .getObservationsInRange(startMs, endMs, lat, lon)
            .filter { it.api == displaySource.id && it.stationId != "NWS_BLEND" }
    }

    @VisibleForTesting
    internal fun buildTodayHighProvenanceMessage(
        appWidgetId: Int,
        today: LocalDate,
        displaySource: WeatherSource,
        forecastWeather: ForecastEntity?,
        dailyActual: DailyHistory?,
        todayDay: DailyForecastGraphRenderer.DayData,
        currentTemp: Float?,
        observedAt: Long?,
        observations: List<ObservationEntity>,
    ): String {
        val stationMaxes = formatStationMaxes(observations)
        val obsSpan = formatObservationSpan(observations)
        return "widget=$appWidgetId date=$today source=${displaySource.id} " +
            "forecastHigh=${formatTempValue(forecastWeather?.highTemp)} forecastLow=${formatTempValue(forecastWeather?.lowTemp)} " +
            "dailyActualHigh=${formatTempValue(dailyActual?.highTemp)} dailyActualLow=${formatTempValue(dailyActual?.lowTemp)} " +
            "currentTemp=${formatTempValue(currentTemp)} observedAt=${formatLocalTime(observedAt)} " +
            "graphObservedHigh=${formatTempValue(todayDay.solidLineHigh)} graphObservedLow=${formatTempValue(todayDay.solidLineLow)} " +
            "graphForecastHigh=${formatTempValue(todayDay.dashedLineHigh)} graphForecastLow=${formatTempValue(todayDay.dashedLineLow)} " +
            "graphGhostHigh=${formatTempValue(todayDay.ghostLineHigh)} graphSnapshotHigh=${formatTempValue(todayDay.snapshotHigh)} " +
            "obsRows=${observations.size} obsSpan=$obsSpan stationMaxes=[$stationMaxes]"
    }

    private fun formatStationMaxes(observations: List<ObservationEntity>): String {
        if (observations.isEmpty()) return "none"
        return observations
            .groupBy { it.stationId }
            .mapNotNull { (stationId, rows) ->
                val maxRow = rows.maxByOrNull { it.temperature } ?: return@mapNotNull null
                val minRow = rows.minByOrNull { it.temperature }
                StationExtremeSummary(
                    stationId = stationId,
                    maxTemp = maxRow.temperature,
                    maxAt = maxRow.timestamp,
                    minTemp = minRow?.temperature,
                    distanceKm = maxRow.distanceKm,
                    rowCount = rows.size,
                )
            }
            .sortedWith(compareByDescending<StationExtremeSummary> { it.maxTemp }.thenBy { it.distanceKm })
            .take(6)
            .joinToString("|") { summary ->
                "${summary.stationId}(max=${formatTempValue(summary.maxTemp)}@${formatLocalTime(summary.maxAt)}," +
                    "min=${formatTempValue(summary.minTemp)},n=${summary.rowCount},d=${formatDistance(summary.distanceKm)}km)"
            }
    }

    private data class StationExtremeSummary(
        val stationId: String,
        val maxTemp: Float,
        val maxAt: Long,
        val minTemp: Float?,
        val distanceKm: Float,
        val rowCount: Int,
    )

    private fun formatObservationSpan(observations: List<ObservationEntity>): String {
        if (observations.isEmpty()) return "none"
        return "${formatLocalTime(observations.minOf { it.timestamp })}..${formatLocalTime(observations.maxOf { it.timestamp })}"
    }

    private fun formatTempValue(value: Float?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: "null"

    private fun formatDistance(value: Float): String =
        String.format(Locale.US, "%.2f", value)

    private fun formatLocalTime(timestampMs: Long?): String {
        if (timestampMs == null) return "null"
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        return Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(formatter)
    }
}
