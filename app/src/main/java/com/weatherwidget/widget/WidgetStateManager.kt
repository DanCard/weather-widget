package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.BuildConfig
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.SharedPreferencesUtil
import java.time.LocalDateTime
import javax.inject.Singleton

enum class ViewMode {
    DAILY,
    TEMPERATURE,
    PRECIPITATION,
    CLOUD_COVER,
    ;

    val isGraphMode: Boolean
        get() = this != DAILY

    companion object {
        fun parseOrDefault(name: String?, default: ViewMode): ViewMode =
            if (name.isNullOrBlank()) {
                default
            } else {
                entries.find { it.name == name } ?: default
            }
    }
}

// Zoom is two types on purpose: ZoomStage is the persisted/cycled selection, ZoomWindow is the
// geometry it resolves to against the user's narrow-span setting. See ZoomStage's kdoc.
typealias ZoomStage = com.weatherwidget.shared.graph.ZoomStage
typealias ZoomWindow = com.weatherwidget.shared.graph.ZoomWindow

/**
 * Compatibility facade for widget and global preferences.
 *
 * Raw key ownership, migration, timing, and lifecycle cleanup live in cohesive stores. Existing
 * callers can migrate to narrow stores incrementally without a flag-day rewrite.
 */
@Singleton
class WidgetStateManager internal constructor(
    context: Context,
    eventLogger: WidgetStateEventLogger,
) {
    constructor(context: Context) : this(context, NoOpWidgetStateEventLogger)

    private val context = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        SharedPreferencesUtil.getPrefs(this.context, prefsNameOverride ?: PREFS_NAME)
    }

    private val displayPreferences by lazy {
        WeatherDisplayPreferences(context, prefs)
    }
    private val sourcePreferences by lazy {
        WeatherSourcePreferences(
            context = context,
            prefs = prefs,
            defaultVisibleSources = DEFAULT_VISIBLE_SOURCES,
            eventLogger = eventLogger::log,
        )
    }
    private val presentationStore by lazy {
        WidgetPresentationStateStore(
            prefs,
            narrowSpanHours = { getNarrowZoomSpanHours() },
            multiDayZoomEnabled = { isMultiDayZoomEnabled() },
        )
    }
    private val deltaStore by lazy {
        CurrentTemperatureDeltaStore(prefs)
    }
    private val fetchStateStore by lazy {
        WidgetFetchStateStore(prefs)
    }
    private val locationStore by lazy {
        WidgetLocationStore(context, deltaStore)
    }

    fun getDateOffset(widgetId: Int): Int = presentationStore.dateOffset(widgetId)

    fun setDateOffset(widgetId: Int, offset: Int) {
        presentationStore.setDateOffset(widgetId, offset)
    }

    /** See [WidgetPresentationStateStore.nextHeaderLabelSwap]. */
    fun nextHeaderLabelSwap(widgetId: Int): Int = presentationStore.nextHeaderLabelSwap(widgetId)

    fun navigateLeft(widgetId: Int): Int = presentationStore.navigateDate(widgetId, -1)

    fun navigateRight(widgetId: Int): Int = presentationStore.navigateDate(widgetId, 1)

    fun canNavigateLeft(widgetId: Int): Boolean = getDateOffset(widgetId) > MIN_DATE_OFFSET

    fun canNavigateRight(widgetId: Int): Boolean = getDateOffset(widgetId) < MAX_DATE_OFFSET

    fun resetDateOffset(widgetId: Int) {
        setDateOffset(widgetId, 0)
    }

    fun useCelsius(): Boolean = displayPreferences.useCelsius()

    fun setUseCelsius(value: Boolean) {
        displayPreferences.setUseCelsius(value)
    }

    fun getPersonalStationDiscountPercent(): Int =
        displayPreferences.personalStationDiscountPercent(DEFAULT_PERSONAL_STATION_DISCOUNT)

    fun setPersonalStationDiscountPercent(percent: Int) {
        displayPreferences.setPersonalStationDiscountPercent(percent)
    }

    /** App-wide span (4..8h) of the tight NARROW hourly view. See [ZoomStage.window]. */
    fun getNarrowZoomSpanHours(): Int = displayPreferences.hourlyNarrowSpanHours()

    fun setNarrowZoomSpanHours(hours: Int) {
        displayPreferences.setHourlyNarrowSpanHours(hours)
    }

    /** Whether the tap cycle includes the multi-day [ZoomStage.TWO_DAY] stage. Off by default. */
    fun isMultiDayZoomEnabled(): Boolean = displayPreferences.multiDayZoomEnabled()

    fun setMultiDayZoomEnabled(value: Boolean) {
        displayPreferences.setMultiDayZoomEnabled(value)
    }

    fun showTodayOverlayDelta(): Boolean = displayPreferences.showTodayOverlayDelta()

    fun setShowTodayOverlayDelta(value: Boolean) {
        displayPreferences.setShowTodayOverlayDelta(value)
    }

    fun showTodayOverlayDominantTemp(): Boolean = displayPreferences.showTodayOverlayDominantTemp()

    fun setShowTodayOverlayDominantTemp(value: Boolean) {
        displayPreferences.setShowTodayOverlayDominantTemp(value)
    }

    fun showTodayOverlayDominantAge(): Boolean = displayPreferences.showTodayOverlayDominantAge()

    fun setShowTodayOverlayDominantAge(value: Boolean) {
        displayPreferences.setShowTodayOverlayDominantAge(value)
    }

    fun getPersonalStationWeight(): Double =
        1.0 - getPersonalStationDiscountPercent() / 100.0

    fun getApiKey(source: WeatherSource): String? = sourcePreferences.apiKey(source)

    fun setApiKey(source: WeatherSource, apiKey: String?) {
        sourcePreferences.setApiKey(source, apiKey)
    }

    fun getVisibleSourcesOrder(): List<WeatherSource> = sourcePreferences.visibleSources()

    fun getPrimarySource(): WeatherSource = sourcePreferences.primarySource()

    fun getActiveDisplaySourceIds(): Set<String> = sourcePreferences.activeDisplaySourceIds()

    fun getEffectiveVisibleSourcesOrder(widgetId: Int): List<WeatherSource> =
        getVisibleSourcesOrder()

    fun setVisibleSourcesOrder(sources: List<WeatherSource>) {
        sourcePreferences.setVisibleSources(sources)
    }

    fun setVisibleSourcesOrderForSetup(
        sources: List<WeatherSource>,
        widgetIds: IntArray,
    ): Boolean = sourcePreferences.setVisibleSourcesForSetup(sources, widgetIds)

    fun isSourceVisible(source: WeatherSource): Boolean = sourcePreferences.isVisible(source)

    fun getCurrentDisplaySource(widgetId: Int): WeatherSource =
        sourcePreferences.currentDisplaySource(widgetId)

    fun getNextDisplaySource(widgetId: Int): WeatherSource =
        sourcePreferences.nextDisplaySource(widgetId)

    fun setCurrentDisplaySource(widgetId: Int, source: WeatherSource) {
        sourcePreferences.setCurrentDisplaySource(widgetId, source)
    }

    fun toggleDisplaySource(widgetId: Int): WeatherSource =
        sourcePreferences.toggleDisplaySource(widgetId)

    fun resetToggleState(widgetId: Int) {
        sourcePreferences.resetToggleState(widgetId)
    }

    fun resetAllToggleStates() {
        sourcePreferences.resetAllToggleStates()
    }

    fun getViewMode(widgetId: Int): ViewMode = presentationStore.viewMode(widgetId)

    fun setViewMode(widgetId: Int, mode: ViewMode) {
        presentationStore.setViewMode(widgetId, mode)
    }

    fun toggleViewMode(widgetId: Int): ViewMode =
        presentationStore.toggleViewMode(widgetId)

    fun togglePrecipitationMode(widgetId: Int): ViewMode =
        presentationStore.togglePrecipitationMode(widgetId)

    fun toggleCloudCoverMode(widgetId: Int): ViewMode =
        presentationStore.toggleCloudCoverMode(widgetId)

    fun getHourlyOffset(widgetId: Int): Int = presentationStore.hourlyOffset(widgetId)

    fun setHourlyOffset(widgetId: Int, offset: Int) {
        presentationStore.setHourlyOffset(widgetId, offset)
    }

    fun resolveHourlyCenterTime(
        widgetId: Int,
        now: LocalDateTime,
        zoom: ZoomWindow,
    ): LocalDateTime = presentationStore.resolveHourlyCenterTime(widgetId, now, zoom)

    fun navigateHourlyLeft(widgetId: Int): Int =
        presentationStore.navigateHourly(widgetId, -1)

    fun navigateHourlyRight(widgetId: Int): Int =
        presentationStore.navigateHourly(widgetId, 1)

    fun canNavigateHourlyLeft(widgetId: Int): Boolean =
        getHourlyOffset(widgetId) > MIN_HOURLY_OFFSET

    fun canNavigateHourlyRight(widgetId: Int): Boolean =
        getHourlyOffset(widgetId) < MAX_HOURLY_OFFSET

    /** The user's persisted stage selection. Use [getZoomWindow] when you need hours/geometry. */
    fun getZoomStage(widgetId: Int): ZoomStage = presentationStore.zoom(widgetId)

    /** The stage resolved against the app-wide narrow-span setting: what renderers should use. */
    fun getZoomWindow(widgetId: Int): ZoomWindow =
        getZoomStage(widgetId).window(getNarrowZoomSpanHours())

    fun setZoomLevel(widgetId: Int, zoom: ZoomStage) {
        presentationStore.setZoom(widgetId, zoom)
    }

    fun cycleZoomLevel(widgetId: Int): ZoomStage =
        presentationStore.cycleZoom(widgetId)

    fun getNavJump(widgetId: Int): Int = getZoomWindow(widgetId).navJump

    fun setTransientMessage(widgetId: Int, message: String, expiresAtMs: Long) {
        presentationStore.setTransientMessage(widgetId, message, expiresAtMs)
    }

    fun getActiveTransientMessage(
        widgetId: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): String? = presentationStore.activeTransientMessage(widgetId, nowMs)

    fun clearTransientMessage(widgetId: Int) {
        presentationStore.clearTransientMessage(widgetId)
    }

    fun hasTransientMessagePending(
        widgetId: Int,
        graceMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = presentationStore.hasTransientMessagePending(widgetId, graceMs, nowMs)

    fun wasRainShownToday(widgetId: Int, today: String): Boolean =
        presentationStore.wasRainShownToday(widgetId, today)

    fun markRainShown(widgetId: Int, today: String) {
        presentationStore.markRainShown(widgetId, today)
    }

    fun shouldRefreshMissingData(
        widgetId: Int,
        sourceId: String,
        refreshType: String,
        cooldownMs: Long,
    ): Boolean =
        fetchStateStore.shouldRefreshMissingData(widgetId, sourceId, refreshType, cooldownMs)

    fun markMissingDataRefreshRequested(
        widgetId: Int,
        sourceId: String,
        refreshType: String,
    ) {
        fetchStateStore.markMissingDataRefreshRequested(widgetId, sourceId, refreshType)
    }

    fun shouldRefreshMissingActuals(widgetId: Int, sourceId: String, cooldownMs: Long): Boolean =
        shouldRefreshMissingData(widgetId, sourceId, "actuals", cooldownMs)

    fun markMissingActualsRefreshRequested(widgetId: Int, sourceId: String) {
        markMissingDataRefreshRequested(widgetId, sourceId, "actuals")
    }

    fun shouldFetchCurrentTempForSource(sourceId: String, minIntervalMs: Long): Boolean =
        fetchStateStore.shouldFetchCurrentTempForSource(sourceId, minIntervalMs)

    fun markCurrentTempFetched(sourceId: String) {
        fetchStateStore.markCurrentTempFetched(sourceId)
    }

    fun getWidgetLocation(widgetId: Int): Pair<Double, Double>? =
        locationStore.resolve(widgetId)?.let { it.latitude to it.longitude }

    fun getStoredWidgetLocation(widgetId: Int): Pair<Double, Double>? =
        locationStore.stored(widgetId)?.let { it.latitude to it.longitude }

    fun setWidgetLocations(widgetIds: IntArray, lat: Double, lon: Double) {
        locationStore.set(widgetIds, lat, lon)
    }

    /**
     * Returns [widgetIds] to the "no location" state. The placeholder for "GPS never resolved" is the
     * *absence* of coordinates — it used to be Google-HQ coordinates, which the fetch path could not
     * distinguish from a real choice.
     */
    fun clearWidgetLocations(widgetIds: IntArray) {
        widgetIds.forEach(locationStore::clearWidget)
    }

    fun getCurrentTempDeltaState(
        widgetId: Int,
        source: WeatherSource,
    ): CurrentTemperatureDeltaState? = deltaStore.get(widgetId, source)

    fun setCurrentTempDeltaState(
        widgetId: Int,
        source: WeatherSource,
        state: CurrentTemperatureDeltaState,
    ) {
        deltaStore.set(widgetId, source, state)
    }

    fun clearCurrentTempDeltaState(
        widgetId: Int,
        source: WeatherSource? = null,
    ) {
        deltaStore.clear(widgetId, source)
    }

    fun getLastGraphRender(widgetId: Int): LastGraphRenderState? =
        presentationStore.lastGraphRender(widgetId)

    fun setLastGraphRender(widgetId: Int, state: LastGraphRenderState) {
        presentationStore.setLastGraphRender(widgetId, state)
    }

    /**
     * True when a repaint fell due while the screen was off and `WidgetPaintCoordinator` skipped it
     * outright. The bitmap on screen may predate an arbitrary number of fetches, so the next paint
     * that does run must rebuild rather than trust [GraphRepaintGate]'s cheaper signals.
     *
     * Deliberately global, not per-widget: the skip happens before widget ids are enumerated, so
     * there is no widget to attribute it to.
     */
    fun isPaintOwed(): Boolean = prefs.getBoolean(KEY_PAINT_OWED, false)

    fun setPaintOwed(owed: Boolean) {
        if (owed) {
            prefs.edit().putBoolean(KEY_PAINT_OWED, true).apply()
        } else {
            prefs.edit().remove(KEY_PAINT_OWED).apply()
        }
    }

    fun getSourceFailureCount(source: WeatherSource): Int =
        fetchStateStore.sourceFailureCount(source)

    fun isSourceErrored(source: WeatherSource): Boolean =
        fetchStateStore.isSourceErrored(source, SOURCE_FAILURE_WATERMARK_THRESHOLD)

    fun recordSourceFetchSuccess(source: WeatherSource) {
        fetchStateStore.recordSourceFetchSuccess(source)
    }

    fun recordSourceFetchFailure(source: WeatherSource, errorCode: String? = null) {
        fetchStateStore.recordSourceFetchFailure(source, errorCode)
    }

    fun getSourceLastErrorCode(source: WeatherSource): String? =
        fetchStateStore.sourceLastErrorCode(source)

    fun getSourceLastFailureTime(source: WeatherSource): Long? =
        fetchStateStore.sourceLastFailureTime(source)

    fun clearWidgetState(widgetId: Int) {
        val editor = prefs.edit()
        presentationStore.clearWidget(widgetId, editor)
        sourcePreferences.clearWidget(widgetId, editor)
        deltaStore.clearWidget(widgetId, editor)
        fetchStateStore.clearWidget(widgetId, editor)
        editor.apply()
        locationStore.clearWidget(widgetId)
    }

    /**
     * What the last full graph-bitmap render drew, for [GraphRepaintGate] to compare against.
     *
     * [dataWatermarkMs] is null for a render recorded before watermark tracking existed — an app
     * upgrade with prefs already on disk. That is deliberately distinct from
     * [ObservationWatermark.NONE] (rendered with no observations to measure): the former must force
     * one rebuild, the latter must not.
     */
    data class LastGraphRenderState(
        val renderMs: Long,
        val displayedTemp: String?,
        val dataWatermarkMs: Long? = null,
    )

    companion object {
        private const val PREFS_NAME = "widget_state_prefs"
        const val DEFAULT_TEST_PREFS_NAME = "widget_state_prefs_android_test"

        @Volatile
        private var prefsNameOverride: String? = null

        /** Global (not per-widget) flag: a paint was skipped for screen-off and is still owed. */
        const val KEY_PAINT_OWED = "widget_paint_owed"

        const val SOURCE_FAILURE_WATERMARK_THRESHOLD = 3
        const val DEFAULT_PERSONAL_STATION_DISCOUNT = 95

        const val MIN_DATE_OFFSET = -30
        const val MAX_DATE_OFFSET = 14
        const val MIN_HOURLY_OFFSET = -720
        const val MAX_HOURLY_OFFSET = 720
        const val HOURLY_NAV_JUMP = 6

        private val DEFAULT_VISIBLE_SOURCES =
            if (BuildConfig.DEBUG) {
                listOf(
                    WeatherSource.NWS,
                    WeatherSource.OPEN_METEO,
                    WeatherSource.SILURIAN,
                    WeatherSource.TOMORROW_IO,
                )
            } else {
                listOf(
                    WeatherSource.NWS,
                    WeatherSource.OPEN_METEO,
                    WeatherSource.SILURIAN,
                )
            }

        @Synchronized
        fun setPrefsNameOverrideForTesting(prefsName: String?) {
            prefsNameOverride = prefsName
        }

        fun getPrefsNameForTesting(): String = prefsNameOverride ?: PREFS_NAME
    }
}
