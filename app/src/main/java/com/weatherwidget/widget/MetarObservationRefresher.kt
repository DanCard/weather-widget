package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.MetarObservationSource
import com.weatherwidget.data.repository.RoomObservationWriteHost
import com.weatherwidget.shared.observations.ObservationWriteRun
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
import com.weatherwidget.shared.util.MetarFetchPolicy
import com.weatherwidget.shared.util.MetarFetchWindow

/**
 * Android's [StationNetworkRefreshRun.Host] over Room and [MetarObservationSource]; pinned to
 * desktop's SQLite host by `StationNetworkHostContract` (`:shared` testFixtures).
 */
internal class AndroidStationNetworkHost(
    private val context: Context,
    private val db: WeatherDatabase,
    private val source: MetarObservationSource,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    override val lat: Double,
    override val lon: Double,
) : StationNetworkRefreshRun.Host {
    override suspend fun backgroundSources(): List<WeatherSource> =
        SourceFetchGateLoader.backgroundSources(context, widgetStateManager, lat to lon)

    override suspend fun displayedSourceIds(): Set<String> =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            .map { widgetStateManager.getCurrentDisplaySource(it).id }
            .distinct()
            .toSet()

    override suspend fun newestStoredTimestampMs(api: String): Long? =
        db.observationDao().getNewestTimestampForApi(api, lat, lon)

    override suspend fun fetchMetar(hours: Int): List<ObservationReading> =
        source.fetchObservations(lat, lon, hours = hours).map { it.toReading() }

    override fun writeHost(): ObservationWriteRun.Host =
        RoomObservationWriteHost(db.observationDao(), db.dailyHistoryDao(), appLogDao, lat, lon)

    override suspend fun log(tag: String, message: String, level: String) {
        appLogDao.log(tag, message, level)
    }
}

/**
 * Thin Android facade over `:shared` [StationNetworkRefreshRun] for METAR observations.
 *
 * METAR has no schedule of its own — [MetarFetchPolicy] derives its tier from what is visible and
 * what is displayed, and each caller declares which tiers ITS run covers:
 *
 * | caller | tiers accepted | cadence it rides |
 * |---|---|---|
 * | current-temp branch | `PRIMARY` | `CurrentTempFetchPolicy` — 10 / 16 min charging, 45 min opportunistic above 65 % |
 * | non-primary branch | `NON_PRIMARY` | `NonPrimaryObservationPolicy` — 30 min, charging AND screen-on |
 * | full sync / targeted tail | **both** | the battery-aware main fetch, plus every user-initiated refresh and source toggle |
 */
internal class MetarObservationRefresher(
    private val context: Context,
    private val source: MetarObservationSource,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
) {
    private val runner = StationNetworkRefreshRun()

    /**
     * [consumerSources] defaults to every enabled source; [refreshIfDue] passes only those still
     * fetched in the background ([SourceFetchGateLoader]), so a borrower on demand only no longer
     * keeps METAR fetched.
     */
    fun currentTier(
        consumerSources: List<WeatherSource> = widgetStateManager.getVisibleSourcesOrder(),
    ): MetarFetchPolicy.Tier {
        val activeSourceIds = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
            .map { widgetStateManager.getCurrentDisplaySource(it).id }
            .distinct()
            .toSet()
        return MetarFetchPolicy.tierFor(consumerSources, activeSourceIds)
    }

    internal fun hostFor(latitude: Double, longitude: Double): StationNetworkRefreshRun.Host =
        AndroidStationNetworkHost(
            context = context,
            db = WeatherDatabase.getDatabase(context),
            source = source,
            widgetStateManager = widgetStateManager,
            appLogDao = appLogDao,
            lat = latitude,
            lon = longitude,
        )

    companion object {
        const val DEEP_HOURS = MetarFetchWindow.DEEP_HOURS
    }

    suspend fun refreshIfDue(
        acceptTiers: Set<MetarFetchPolicy.Tier>,
        latitude: Double,
        longitude: Double,
        reason: String,
        hours: Int? = null,
    ) {
        runner.refreshIfDue(hostFor(latitude, longitude), acceptTiers, reason, hours)
    }
}
