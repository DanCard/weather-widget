package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.BuildConfig
import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.BuiltInApiKeys
import com.weatherwidget.shared.util.RecentLocationsHelper
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.SharedPreferencesUtil
import java.time.LocalDateTime
import javax.inject.Singleton

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
    internal val dominantTempWatchPreferences by lazy {
        DominantTempWatchPreferences(prefs)
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

    fun showDailyZoomOutButton(): Boolean = displayPreferences.showDailyZoomOutButton()

    fun setShowDailyZoomOutButton(value: Boolean) {
        displayPreferences.setShowDailyZoomOutButton(value)
    }

    /**
     * One-shot watch: notify once when the dominant station behind the primary source reports a
     * different temperature, then clear itself. See
     * [com.weatherwidget.shared.notify.DominantTempWatch].
     */
    fun notifyOnDominantTempChange(): Boolean = dominantTempWatchPreferences.isArmed()

    /** Arming always drops any previous baseline — see [DominantTempWatchPreferences.setArmed]. */
    fun setNotifyOnDominantTempChange(value: Boolean) {
        dominantTempWatchPreferences.setArmed(value)
    }

    /**
     * Observe raw preference writes. Used by Settings to notice the dominant-temp watch disarming
     * itself under an open screen — the worker that fires it lives in this same process.
     *
     * Callers must hold a strong reference to the listener: `SharedPreferences` keeps only a weak
     * one, so a listener created inline is collected and silently stops firing.
     */
    fun registerPreferenceListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterPreferenceListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun getPersonalStationWeight(): Double =
        1.0 - getPersonalStationDiscountPercent() / 100.0

    fun getApiKey(source: WeatherSource): String? = sourcePreferences.apiKey(source)

    /** Which feed supplies [source]'s actuals, or null for the default. See ActualsProviderResolver. */
    fun getActualsProvider(source: WeatherSource): WeatherSource? =
        sourcePreferences.actualsProvider(source)

    fun setActualsProvider(source: WeatherSource, provider: WeatherSource?) {
        sourcePreferences.setActualsProvider(source, provider)
    }

    fun setApiKey(source: WeatherSource, apiKey: String?) {
        sourcePreferences.setApiKey(source, apiKey)
    }

    /** Enabled sources usable at the active location — what fetches, displays and cycles. */
    fun getVisibleSourcesOrder(): List<WeatherSource> = sourcePreferences.visibleSources()

    /** The user's enabled list, unfiltered by location. Settings only. */
    fun getEnabledSourcesOrder(): List<WeatherSource> = sourcePreferences.enabledSources()

    fun isSourceUnavailableHere(source: WeatherSource): Boolean = sourcePreferences.isUnavailableHere(source)

    fun getPrimarySource(): WeatherSource = sourcePreferences.primarySource()

    fun getActiveDisplaySourceIds(): Set<String> = sourcePreferences.activeDisplaySourceIds()

    /** See [WeatherSourcePreferences.consumePendingSourceSwitch]. */
    fun consumePendingSourceSwitch(): WeatherSource? = sourcePreferences.consumePendingSourceSwitch()

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
        getHourlyOffset(widgetId) > NavigationUtils.MIN_HOURLY_OFFSET

    fun canNavigateHourlyRight(widgetId: Int): Boolean =
        getHourlyOffset(widgetId) < NavigationUtils.MAX_HOURLY_OFFSET

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

    fun missingActualsRequestedAtMs(widgetId: Int, sourceId: String): Long =
        fetchStateStore.missingDataRequestedAtMs(widgetId, sourceId, "actuals")

    fun markObservationBackfillAttempted(siteKey: String) =
        fetchStateStore.markObservationBackfillAttempted(siteKey)

    fun observationBackfillAttemptedAtMs(siteKey: String): Long =
        fetchStateStore.observationBackfillAttemptedAtMs(siteKey)

    fun fetchStateNowMs(): Long = fetchStateStore.nowMs()

    fun currentTempAttemptMs(sourceId: String): Long = fetchStateStore.currentTempAttemptMs(sourceId)

    fun markCurrentTempFetched(sourceId: String, ms: Long) {
        fetchStateStore.markCurrentTempFetched(sourceId, ms)
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

    /**
     * The place name a setup-screen location change is fetching for, while the
     * "Getting weather for {place}…" interstitial is (or is about to be) on screen; null otherwise.
     *
     * Set synchronously by `LocationUpdater` before the force-refresh worker is enqueued, so every
     * later paint path agrees on the state: an empty-cache repaint re-asserts the interstitial
     * instead of pushing a blank graph, the first render with forecast rows clears it, and a sync
     * failure swaps it for the "Tap to refresh" fallback rather than leaving it up forever.
     *
     * Global, like [isPaintOwed]: the active location is app-wide, so the fetch it waits on is too.
     */
    fun getPendingLocationFetch(): String? = prefs.getString(KEY_PENDING_LOCATION_FETCH, null)

    fun setPendingLocationFetch(placeName: String) {
        prefs.edit().putString(KEY_PENDING_LOCATION_FETCH, placeName).apply()
    }

    fun clearPendingLocationFetch() {
        prefs.edit().remove(KEY_PENDING_LOCATION_FETCH).apply()
    }

    /**
     * When [sourceId]'s last LONG forecast fetch (≈ 8 days) at the site was made, or null when it never
     * was here ([com.weatherwidget.data.remote.ForecastHorizonCadence]). One marker per source: a
     * different site reads null, so a new place is LONG-due at once.
     */
    fun getLastLongFetch(sourceId: String, lat: Double, lon: Double): Long? = readMarker(KEY_LONG_FETCH_PREFIX + sourceId, lat, lon)

    fun markLongFetch(sourceId: String, lat: Double, lon: Double, atMs: Long) = writeMarker(KEY_LONG_FETCH_PREFIX + sourceId, lat, lon, atMs)

    private fun readMarker(key: String, lat: Double, lon: Double): Long? =
        com.weatherwidget.data.remote.ForecastHorizonCadence.Marker.decode(prefs.getString(key, null))?.forSite(lat, lon)

    private fun writeMarker(key: String, lat: Double, lon: Double, atMs: Long) {
        prefs.edit()
            .putString(key, com.weatherwidget.data.remote.ForecastHorizonCadence.Marker.at(lat, lon, atMs).encode())
            .apply()
    }

    fun getRecentLocations(): List<RecentLocation> {
        val raw = prefs.getString(KEY_RECENT_LOCATIONS, null)
        return RecentLocationsHelper.decodeFromJson(raw)
    }

    fun addRecentLocation(location: RecentLocation) {
        val current = getRecentLocations()
        val updated = RecentLocationsHelper.addRecent(current, location)
        prefs.edit().putString(KEY_RECENT_LOCATIONS, RecentLocationsHelper.encodeToJson(updated)).apply()
    }

    fun removeRecentLocation(location: RecentLocation) {
        val current = getRecentLocations()
        val updated = RecentLocationsHelper.removeRecent(current, location)
        prefs.edit().putString(KEY_RECENT_LOCATIONS, RecentLocationsHelper.encodeToJson(updated)).apply()
    }

    fun clearRecentLocations() {
        prefs.edit().remove(KEY_RECENT_LOCATIONS).apply()
    }

    fun getSourceFailureCount(source: WeatherSource): Int =
        fetchStateStore.sourceFailureCount(source)

    fun isSourceErrored(source: WeatherSource): Boolean =
        fetchStateStore.isSourceErrored(source, SOURCE_FAILURE_WATERMARK_THRESHOLD)

    fun recordSourceFetchSuccess(source: WeatherSource) {
        fetchStateStore.recordSourceFetchSuccess(source)
    }

    fun recordSourceFetchFailure(source: WeatherSource, errorCode: String? = null, detail: String? = null) {
        fetchStateStore.recordSourceFetchFailure(source, errorCode, SOURCE_FAILURE_WATERMARK_THRESHOLD, detail)
        // A 429 that is not a daily quota (that one is QUOTA_DAILY, FetchErrorCode) is a short-window
        // limit: bannered at once for a minute, not after the 3-failure streak.
        if (errorCode == "HTTP_429") fetchStateStore.recordRateLimit(source, partial = false, detail = detail)
    }

    fun getSourceLastErrorCode(source: WeatherSource): String? =
        fetchStateStore.sourceLastErrorCode(source)

    fun getSourceLastFailureTime(source: WeatherSource): Long? =
        fetchStateStore.sourceLastFailureTime(source)

    fun getSourceLastFailureDetail(source: WeatherSource): String? =
        fetchStateStore.sourceLastFailureDetail(source)

    fun getSourceBannerSince(source: WeatherSource): Long? =
        fetchStateStore.sourceBannerSince(source)

    fun recordProductQuota(source: WeatherSource, product: ForecastProduct, untilMs: Long, detail: String?) =
        fetchStateStore.recordProductQuota(source, product, untilMs, detail)

    fun clearProductQuota(source: WeatherSource, product: ForecastProduct) =
        fetchStateStore.clearProductQuota(source, product)

    internal fun getProductQuota(source: WeatherSource, product: ForecastProduct, nowMs: Long = System.currentTimeMillis()) =
        fetchStateStore.productQuota(source, product, nowMs)

    fun recordRateLimit(source: WeatherSource, partial: Boolean, detail: String?) =
        fetchStateStore.recordRateLimit(source, partial, detail)

    internal fun getRateLimit(source: WeatherSource, nowMs: Long = System.currentTimeMillis()) =
        fetchStateStore.rateLimit(source, nowMs)

    /**
     * The failure pill a view of [product] draws for [source]: a source-wide failure streak first
     * (it affects every view), else a product quota block — that product's, else the other's
     * ([com.weatherwidget.data.remote.QuotaNotice.bannerBlock]; an hourly-only block on the daily view
     * was silent until 2026-10-21) — else a rate-limit refusal from the last minute — on every view,
     * the first refusal, no streak needed (user, 2026-10-16:
     * plans/261016-quota-refusals-always-show-a-banner.md).
     */
    fun viewWatermark(
        source: WeatherSource,
        product: ForecastProduct,
        nowMs: Long = System.currentTimeMillis(),
    ): ViewWatermark {
        if (isSourceErrored(source)) {
            return ViewWatermark(
                show = true,
                errorCode = getSourceLastErrorCode(source),
                failureTimeMs = getSourceLastFailureTime(source),
                bannerSinceMs = getSourceBannerSince(source),
            )
        }
        val (blocked, quota) = com.weatherwidget.data.remote.QuotaNotice.bannerBlock(product) { getProductQuota(source, it, nowMs) }
            ?: return getRateLimit(source, nowMs)?.let {
                ViewWatermark(
                    show = true,
                    errorCode = com.weatherwidget.data.remote.RateLimitNotice.errorCode(it.partial),
                    failureTimeMs = it.atMs,
                    bannerSinceMs = it.atMs,
                )
            } ?: ViewWatermark.NONE
        return ViewWatermark(
            show = true,
            errorCode = com.weatherwidget.data.remote.GoogleQuota.errorCodeFor(blocked),
            failureTimeMs = quota.sinceMs,
            bannerSinceMs = quota.sinceMs,
        )
    }

    data class ViewWatermark(
        val show: Boolean,
        val errorCode: String?,
        val failureTimeMs: Long?,
        val bannerSinceMs: Long?,
    ) {
        companion object {
            val NONE = ViewWatermark(false, null, null, null)
        }
    }

    fun getLastInteractedWidgetId(): Int? {
        val id = prefs.getInt(KEY_LAST_INTERACTED_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return if (id != AppWidgetManager.INVALID_APPWIDGET_ID) id else null
    }

    fun setLastInteractedWidgetId(widgetId: Int) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        prefs.edit().putInt(KEY_LAST_INTERACTED_WIDGET_ID, widgetId).apply()
    }

    fun clearWidgetState(widgetId: Int) {
        val editor = prefs.edit()
        if (getLastInteractedWidgetId() == widgetId) {
            editor.remove(KEY_LAST_INTERACTED_WIDGET_ID)
        }
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

        /** Global: ID of the most recently interacted widget for priority ordering. */
        const val KEY_LAST_INTERACTED_WIDGET_ID = "last_interacted_widget_id"

        /** Global: place name of a setup-screen location change whose first fetch has not landed. */
        const val KEY_PENDING_LOCATION_FETCH = "widget_pending_location_fetch"
        private const val KEY_LONG_FETCH_PREFIX = "forecast_long_fetch_"

        /** Global: JSON array of recent locations. */
        const val KEY_RECENT_LOCATIONS = "recent_locations"

        const val SOURCE_FAILURE_WATERMARK_THRESHOLD = 3
        const val DEFAULT_PERSONAL_STATION_DISCOUNT = 95

        const val MIN_DATE_OFFSET = -30
        const val MAX_DATE_OFFSET = 14
        const val HOURLY_NAV_JUMP = 6

        private val DEFAULT_VISIBLE_SOURCES =
            DefaultVisibleSources.forBuild(
                debug = BuildConfig.DEBUG,
                hasBakedGoogleKey = BuiltInApiKeys.baked(WeatherSource.GOOGLE_WEATHER) != null,
            )

        @Synchronized
        fun setPrefsNameOverrideForTesting(prefsName: String?) {
            prefsNameOverride = prefsName
        }

        fun getPrefsNameForTesting(): String = prefsNameOverride ?: PREFS_NAME
    }
}
