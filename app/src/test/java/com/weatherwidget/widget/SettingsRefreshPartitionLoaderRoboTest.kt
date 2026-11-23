package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource.NWS
import com.weatherwidget.data.model.WeatherSource.OPEN_METEO
import com.weatherwidget.data.model.WeatherSource.SILURIAN
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Settings → Refresh data after a move: real [FetchMetadata] prefs, [WidgetStateManager] and
 * [SettingsRefreshPartitionLoader]. A refresh at Scottsdale must not make Mountain View fresh
 * (desktop 2026-11-22, same site-blind stamp on Android). Fails on the old source-only key.
 * plans/261122-user-refresh-stamps-per-site.md
 */
@Category(LongDuration::class)
class SettingsRefreshPartitionLoaderRoboTest : RobolectricTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mountainView = 37.4166014 to -122.0888722
    private val scottsdale = 33.6451828 to -111.9112613

    @Test
    fun `a refresh at one site does not skip sources at another`() {
        val stateManager = WidgetStateManager(context).apply { setVisibleSourcesOrder(listOf(NWS, OPEN_METEO, SILURIAN)) }
        val now = System.currentTimeMillis()

        // The Scottsdale click, stamped as SettingsActivity stamps it.
        val clicked = SettingsRefreshPartitionLoader.load(context, stateManager, scottsdale, now)
        clicked.fetch.forEach {
            FetchMetadata.setLastUserRefreshTime(context, now, SettingsRefreshPartitionLoader.userRefreshKey(it, scottsdale))
        }
        // The move fetched NWS only at Mountain View.
        FetchMetadata.setLastForecastSourceSuccessTime(context, NWS.id, mountainView.first, mountainView.second, now + 100_000L)

        val later = now + 126_000L
        val atNewSite = SettingsRefreshPartitionLoader.load(context, stateManager, mountainView, later)
        val atOldSite = SettingsRefreshPartitionLoader.load(context, stateManager, scottsdale, later)
        println("click: ${clicked.logLine()}")
        println("new site: ${atNewSite.logLine()}")
        println("old site: ${atOldSite.logLine()}")

        assertEquals(setOf(NWS.id, OPEN_METEO.id, SILURIAN.id), clicked.fetch.toSet())
        assertEquals(setOf(OPEN_METEO.id, SILURIAN.id), atNewSite.fetch.toSet())
        assertEquals(setOf(NWS.id), atNewSite.fresh.keys)
        assertTrue(atOldSite.allFresh)
    }
}
