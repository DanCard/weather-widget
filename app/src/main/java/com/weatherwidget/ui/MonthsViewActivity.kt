package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.RetentionPolicy
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.months.MonthsDayValue
import com.weatherwidget.shared.months.MonthsGraphInput
import com.weatherwidget.shared.months.MonthsSeriesBuilder
import com.weatherwidget.shared.months.MonthsSourceDepth
import com.weatherwidget.shared.months.SourceDepth
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.shared.months.MonthsViewport
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.HourlyForecastLoader
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetDataBundle
import com.weatherwidget.widget.WidgetDataBundleLoader
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.DailyViewHandler
import com.weatherwidget.widget.handlers.DailyViewLogic
import com.weatherwidget.shared.util.TempUtils
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.widget.handlers.WidgetDimensions
import com.weatherwidget.widget.handlers.WidgetSizeCalculator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import javax.inject.Inject

/**
 * The months view: a line graph of daily highs and lows over months (the zoom-out of the daily
 * view), and — on a tap — the regular daily column view for that day, whatever its age.
 * Gestures and levels: [MonthsScreenLogic]; zoom rules, series and geometry: `:shared`
 * (`com.weatherwidget.shared.months`), which desktop's months view uses too.
 * See plans/261015-months-line-graph-app-screen-zooms-to-daily.md.
 */
@AndroidEntryPoint
class MonthsViewActivity : AppCompatActivity(), MonthsScreenView.Listener {

    @Inject
    lateinit var weatherRepository: WeatherRepository

    @Inject
    lateinit var appLogDao: AppLogDao

    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var graphView: MonthsScreenView
    private lateinit var titleView: TextView

    /** Everything one load read; the column view renders from it without reloading. */
    private class Loaded(
        val lat: Double,
        val lon: Double,
        val source: WeatherSource,
        val today: LocalDate,
        val bundle: WidgetDataBundle,
        val actuals: Map<LocalDate, DailyHistory>,
        val weatherByDate: Map<LocalDate, ForecastEntity>,
        val values: List<MonthsDayValue>,
        val normals: Map<MonthDay, Pair<Float, Float>>,
        /** Another visible source whose history here reaches much further back, or null. */
        val moreHistory: SourceDepth?,
    ) {
        val bounds: ClosedRange<LocalDate>? = MonthsSeriesBuilder.bounds(values)
    }

    private var loaded: Loaded? = null
    private var source: WeatherSource? = null
    private var state = MonthsScreenState()
    private var dragAccumPx = 0f
    private var dailyRender: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Translucent from the start: the column view shows the wallpaper (showWallpaper), and a
        // window's pixel format cannot become translucent later — the old frame would show through.
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setContentView(R.layout.activity_months_view)
        widgetStateManager = WidgetStateManager(this)
        graphView = findViewById(R.id.months_graph)
        titleView = findViewById(R.id.title)
        graphView.listener = this

        DataScreenStyler.background(findViewById(R.id.months_root))
        val sourceButton = findViewById<TextView>(R.id.api_source_button)
        DataScreenStyler.pillButton(sourceButton)
        sourceButton.setOnClickListener { cycleSource() }
        findViewById<ImageButton>(R.id.back_button).setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        val visible = widgetStateManager.getVisibleSourcesOrder()
        val requested = intent.getStringExtra(EXTRA_SOURCE)?.let { id -> visible.find { it.id == id } }
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        source = requested
            ?: widgetId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }?.let { widgetStateManager.getCurrentDisplaySource(it) }
            ?: visible.firstOrNull()
        updateSourceButton()
        graphView.doOnLayout { load() }
    }

    private fun updateSourceButton() {
        findViewById<TextView>(R.id.api_source_button).text = source?.shortDisplayName.orEmpty()
    }

    private fun cycleSource() {
        val visible = widgetStateManager.getVisibleSourcesOrder()
        if (visible.isEmpty()) return
        val index = visible.indexOf(source)
        source = visible[(index + 1).mod(visible.size)]
        updateSourceButton()
        load()
    }

    private fun load() {
        val viewed = source ?: return showEmpty()
        val location = intentLocation() ?: ActiveLocationResolver.current(this) ?: return showEmpty()
        lifecycleScope.launch {
            val startMs = android.os.SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { read(location.first, location.second, viewed) }
            if (source != viewed) return@launch
            loaded = result
            appLogDao.log(
                "MONTHS_VIEW_LOAD",
                "source=${viewed.id} days=${result.values.size} bounds=${result.bounds} " +
                    "actuals=${result.actuals.size} ms=${android.os.SystemClock.elapsedRealtime() - startMs}",
                "INFO",
            )
            // Opened on a day (e.g. from a widget): start in that day's column view.
            val openOn = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (openOn != null && state.mode == MonthsScreenState.Mode.MONTHS && state.dailyColumns == 0) {
                state = MonthsScreenLogic.tapMonths(state, context(), openOn)
                intent.removeExtra(EXTRA_DATE)
            }
            render()
        }
    }

    private fun intentLocation(): Pair<Double, Double>? =
        if (intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON)) {
            intent.getDoubleExtra(EXTRA_LAT, 0.0) to intent.getDoubleExtra(EXTRA_LON, 0.0)
        } else {
            null
        }

    /** Cache-only reads, the same ones the widget's daily view makes, plus the whole `daily_history` record. */
    private suspend fun read(lat: Double, lon: Double, viewed: WeatherSource): Loaded {
        val today = LocalDate.now()
        val loader = WidgetDataBundleLoader(weatherRepository, HourlyForecastLoader(this, widgetStateManager), this)
        val bundle = loader.load(
            latitude = lat,
            longitude = lon,
            networkAllowed = false,
            recomputeActuals = false,
            forceRefresh = false,
            targetSourceId = null,
            fetchContext = null,
            caller = "months_view",
        )
        val actuals = loader.fetchDailyActuals(
            lat = lat,
            lon = lon,
            hourlyForecasts = bundle.hourlyForecasts,
            activeSourceList = listOf(viewed.id),
            recompute = false,
            lookbackDays = RetentionPolicy.DAILY_HISTORY_DAYS,
        )[viewed.id].orEmpty()
        val weatherByDate = DailyViewHandler.buildWeatherByDate(bundle.weatherList, viewed, today)
        val values = MonthsSeriesBuilder.values(
            pastActuals = actuals,
            forecasts = weatherByDate
                .filterValues { it.source == viewed.id && !it.isClimateNormal }
                .mapValues { (_, row) -> row.highTemp to row.lowTemp },
            today = today,
        )
        val database = WeatherDatabase.getDatabase(this)
        val normals = ClimateGapFiller(database.climateNormalDao()).cachedNormalsByMonthDay(lat, lon)
        val moreHistory = MonthsSourceDepth.suggestion(
            currentId = viewed.id,
            depths = readHistoryDepths(database.dailyHistoryDao(), lat, lon, today),
            visibleIds = widgetStateManager.getVisibleSourcesOrder().map { it.id },
        )
        return Loaded(lat, lon, viewed, today, bundle, actuals, weatherByDate, values, normals, moreHistory)
    }

    private fun context(): MonthsScreenContext {
        val shape = widgetShape
        return MonthsScreenContext(
            today = loaded?.today ?: LocalDate.now(),
            baseColumns = shape.cols.coerceAtLeast(1),
            graphWidthDp = (shape.widthDp - CARD_PADDING_X_DP * 2).toFloat(),
            bounds = loaded?.bounds,
        )
    }

    /**
     * The column view is drawn at the shape of the user's widget, so it looks like the widget: the
     * requesting widget, else the largest graph-mode one installed, else a typical 5×2.
     */
    private val widgetShape: WidgetDimensions by lazy {
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, WeatherWidgetProvider::class.java))
        val requested = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val sizes = ids.associateWith { WidgetSizeCalculator.getWidgetSize(this, manager, it) }
        val shape = sizes[requested]?.takeIf { it.rows >= 2 }
            ?: sizes.values.filter { it.rows >= 2 }.maxByOrNull { it.widthDp * it.heightDp }
            ?: WidgetDimensions(
                cols = WidgetSizeCalculator.columnsForWidthDp(FALLBACK_WIDTH_DP),
                rows = WidgetSizeCalculator.rowsForHeightDp(FALLBACK_HEIGHT_DP),
                widthDp = FALLBACK_WIDTH_DP,
                heightDp = FALLBACK_HEIGHT_DP,
                isIconWidth = false,
            )
        Log.d(TAG, "widgetShape ids=${ids.toList()} requested=$requested -> ${shape.widthDp}x${shape.heightDp}dp cols=${shape.cols} rows=${shape.rows}")
        shape
    }

    private fun showEmpty() {
        val today = LocalDate.now()
        val input = MonthsGraphInput(
            MonthsViewport.window(state.span, today), state.span, today, emptyList(), emptyMap(),
            widgetStateManager.useCelsius(),
        )
        graphView.showMonths(input, "", getString(R.string.months_view_empty))
    }

    private fun render() {
        val data = loaded ?: return
        val ctx = context()
        showWallpaper(state.mode == MonthsScreenState.Mode.DAILY)
        showMoreHistory(data.moreHistory.takeIf { state.mode == MonthsScreenState.Mode.MONTHS })
        when (state.mode) {
            MonthsScreenState.Mode.MONTHS -> {
                dailyRender?.cancel()
                val window = MonthsScreenLogic.monthsWindow(state, ctx)
                titleView.setText(R.string.months_view_title)
                graphView.showMonths(
                    MonthsGraphInput(window, state.span, data.today, data.values, data.normals, widgetStateManager.useCelsius()),
                    dateRange(window.start, window.end),
                    getString(R.string.months_view_empty),
                )
            }
            MonthsScreenState.Mode.DAILY -> {
                val (first, last) = MonthsScreenLogic.dailyRange(state, ctx)
                titleView.text = dateRange(first, last)
                val renderState = state
                dailyRender?.cancel()
                dailyRender = lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.Default) { renderDaily(data, renderState, ctx) }
                    if (state == renderState) graphView.showDaily(bitmap)
                }
            }
        }
    }

    /** "NWS has history back to Jun 2. Tap to switch." — tapping switches the screen to that source. */
    private fun showMoreHistory(depth: SourceDepth?) {
        val line = findViewById<TextView>(R.id.months_more_history)
        if (depth == null) {
            line.visibility = android.view.View.GONE
            return
        }
        val target = WeatherSource.fromId(depth.sourceId)
        val zone = ZoneId.systemDefault()
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
            (if (depth.first.year == LocalDate.now().year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
        val date = DateUtils.formatDateTime(this, depth.first.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), flags)
        line.text = getString(R.string.months_view_more_history, target.shortDisplayName, date)
        line.visibility = android.view.View.VISIBLE
        line.setOnClickListener {
            lifecycleScope.launch {
                appLogDao.log("MONTHS_VIEW_MORE_HISTORY", "from=${source?.id} to=${target.id} first=${depth.first} days=${depth.days}", "INFO")
            }
            source = target
            updateSourceButton()
            load()
        }
    }

    /**
     * The column view sits on the home-screen wallpaper, like the widget: its 50 % black background
     * only reads as a card over one. The months graph keeps the opaque data-screen background.
     */
    private fun showWallpaper(show: Boolean) {
        val root = findViewById<android.view.View>(R.id.months_root)
        if (show) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            DataScreenStyler.background(root)
        }
    }

    /**
     * The widget's own column view for this window — same day preparation, same renderer — drawn as
     * a widget card: the widget's size, background, padding and in-bitmap header. Rendered at full
     * resolution (the widget caps its bitmap for RemoteViews; a screen has no such limit).
     */
    private fun renderDaily(data: Loaded, s: MonthsScreenState, ctx: MonthsScreenContext): Bitmap {
        val shape = widgetShape
        val density = resources.displayMetrics.density
        val columns = s.dailyColumns
        val skipYesterday = MonthsScreenLogic.skipYesterday(columns)
        val skipHistory = NavigationUtils.shouldSkipHistory(skipYesterday, s.dailyOffset)
        val centerDate = NavigationUtils.getDisplayCenterDate(ctx.today, s.dailyOffset, skipYesterday)
        val observed = ObservationResolver.resolveObservedCurrentTemp(data.bundle.currentTemps, data.source)
        val days = DailyViewLogic.prepareGraphDayInputs(
            now = LocalDateTime.now(),
            centerDate = centerDate,
            today = ctx.today,
            weatherByDate = data.weatherByDate,
            forecastSnapshots = data.bundle.forecastSnapshots,
            numColumns = columns,
            displaySource = data.source,
            skipYesterday = skipYesterday,
            skipHistory = skipHistory,
            hourlyForecasts = data.bundle.hourlyForecasts,
            stateManager = widgetStateManager,
            dailyActuals = data.actuals,
            climateNormals = data.normals,
            currentTemps = data.bundle.currentTemps,
            currentTemp = observed?.temperature,
            observedAt = observed?.observedAt,
            allowTodayRainChanceLabel = true,
            use24HourClock = android.text.format.DateFormat.is24HourFormat(this),
            todayLabel = getString(R.string.today),
            centerLat = data.lat,
            centerLon = data.lon,
        ).map(DailyViewLogic.PreparedGraphDay::renderDay)

        val useCelsius = widgetStateManager.useCelsius()
        val todayInView = days.any { it.isToday }
        // The widget's header, minus what a past window has no value for: current temperature, its
        // icon and delta, and the rain chance appear only while today is on screen.
        val header = DailyForecastGraphRenderer.HeaderRenderData(
            currentTempText = if (todayInView) observed?.temperature?.let { TempUtils.formatTemp(it, useCelsius) } else null,
            dateText = if (days.size >= HeaderConstants.DATE_MIN_COLUMNS) {
                (if (todayInView) ctx.today else days[(days.size - 1) / 2].date).format(DailyViewHandler.headerDateFormatter())
            } else null,
            apiSourceText = data.source.shortDisplayName,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(shape.rows),
            showIcon = false,
            showDelta = false,
            showPrecip = false,
        )

        val cardWidth = (shape.widthDp * density).toInt()
        val cardHeight = (shape.heightDp * density).toInt()
        val graph = DailyForecastGraphRenderer.renderGraph(
            this,
            days,
            ((shape.widthDp - CARD_PADDING_X_DP * 2) * density).toInt(),
            ((shape.heightDp - CARD_PADDING_Y_DP * 2) * density).toInt(),
            numColumns = days.size,
            headerData = header,
            useCelsius = useCelsius,
        ).bitmap
        val card = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(card)
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.widget_background)?.apply {
            setBounds(0, 0, cardWidth, cardHeight)
            draw(canvas)
        }
        canvas.drawBitmap(graph, CARD_PADDING_X_DP * density, CARD_PADDING_Y_DP * density, null)
        graph.recycle()
        return card
    }

    private fun dateRange(first: LocalDate, last: LocalDate): String {
        val zone = ZoneId.systemDefault()
        val startMs = first.atStartOfDay(zone).toInstant().toEpochMilli()
        // Noon, not midnight: formatDateRange treats an end exactly at midnight as exclusive and
        // would print one day short ("Oct 2 – 9" for a window ending on the 10th).
        val endMs = last.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return DateUtils.formatDateRange(this, startMs, endMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

    private fun update(next: MonthsScreenState, event: String) {
        if (next == state) return
        val levelChanged = next.mode != state.mode || next.span != state.span || next.dailyColumns != state.dailyColumns
        state = next
        // Zoom levels and taps only; drags step too often to be worth a row each.
        if (levelChanged) {
            lifecycleScope.launch {
                appLogDao.log(
                    "MONTHS_VIEW_ZOOM",
                    "event=$event mode=${next.mode} span=${next.span} end=${next.monthsEnd} " +
                        "offset=${next.dailyOffset} columns=${next.dailyColumns}",
                    "INFO",
                )
            }
        }
        render()
    }

    private fun goBack() {
        val next = MonthsScreenLogic.back(state)
        if (next == null) finish() else update(next, "back")
    }

    override fun onPinchStep(zoomOut: Boolean) {
        if (loaded == null) return
        val ctx = context()
        update(if (zoomOut) MonthsScreenLogic.zoomOut(state, ctx) else MonthsScreenLogic.zoomIn(state, ctx), if (zoomOut) "pinch_out" else "pinch_in")
    }

    override fun onDrag(dxPx: Float) {
        if (loaded == null) return
        dragAccumPx += dxPx
        val ctx = context()
        when (state.mode) {
            MonthsScreenState.Mode.MONTHS -> {
                val geometry = graphView.lastGeometry ?: return
                val window = MonthsScreenLogic.monthsWindow(state, ctx)
                val days = MonthsViewport.dragDays(dragAccumPx, geometry.plotWidth, window)
                if (days != 0) {
                    dragAccumPx += days * geometry.plotWidth / window.dayCount
                    update(MonthsScreenLogic.panMonths(state, ctx, days), "drag")
                }
            }
            MonthsScreenState.Mode.DAILY -> {
                val dayWidth = graphView.dailyDrawnWidthPx() / state.dailyColumns.coerceAtLeast(1)
                val steps = -(dragAccumPx / dayWidth).toInt()
                if (steps != 0) {
                    dragAccumPx += steps * dayWidth
                    update(MonthsScreenLogic.panDaily(state, ctx, steps), "drag")
                }
            }
        }
    }

    override fun onDragEnd() {
        dragAccumPx = 0f
    }

    override fun onTap(x: Float, y: Float) {
        if (loaded == null || state.mode != MonthsScreenState.Mode.MONTHS) return
        val geometry = graphView.lastGeometry ?: return
        update(MonthsScreenLogic.tapMonths(state, context(), geometry.dateAt(x)), "tap")
    }

    companion object {
        private const val TAG = "MonthsViewActivity"

        /** Graph inset inside the widget card: `computeBitmapDimensions`' 24 dp / 16 dp, per side. */
        private const val CARD_PADDING_X_DP = 12
        private const val CARD_PADDING_Y_DP = 8

        /** A typical 5×2 phone widget, when none is installed. */
        private const val FALLBACK_WIDTH_DP = 340
        private const val FALLBACK_HEIGHT_DP = 190
        const val EXTRA_LAT = "com.weatherwidget.months.LAT"
        const val EXTRA_LON = "com.weatherwidget.months.LON"
        const val EXTRA_SOURCE = "com.weatherwidget.months.SOURCE"

        /** Optional: open on this day's column view instead of the months graph. */
        const val EXTRA_DATE = "com.weatherwidget.months.DATE"

        /**
         * How far back each source's history reaches at this site: one site-box read over the whole
         * record, every source, judged by the shared rule ([MonthsSourceDepth.depths]).
         */
        internal suspend fun readHistoryDepths(
            dao: com.weatherwidget.data.local.DailyHistoryDao,
            lat: Double,
            lon: Double,
            today: LocalDate,
        ): List<SourceDepth> {
            val start = MonthsSeriesBuilder.historyStart(today).toEpochDay() * DAY_MS
            val end = today.minusDays(1).toEpochDay() * DAY_MS
            val rows = dao.getExtremesInRange(start, end, lat, lon).map { it.toDailyHistory() }
            return MonthsSourceDepth.depths(rows, today)
        }

        private const val DAY_MS = 86_400_000L

        fun intent(context: Context, source: WeatherSource? = null, lat: Double? = null, lon: Double? = null): Intent =
            Intent(context, MonthsViewActivity::class.java).apply {
                source?.let { putExtra(EXTRA_SOURCE, it.id) }
                if (lat != null && lon != null) {
                    putExtra(EXTRA_LAT, lat)
                    putExtra(EXTRA_LON, lon)
                }
            }.also { Log.d(TAG, "intent source=${source?.id} lat=$lat lon=$lon") }
    }
}
