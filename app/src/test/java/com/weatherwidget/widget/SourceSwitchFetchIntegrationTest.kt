package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Real [SourceSwitchFetch] + Room + WidgetStateManager + WorkManager (test driver) + bound widgets:
 * a source becoming primary with nothing cached shows its banner on every widget and enqueues an
 * expedited, forced sync targeted at it; with a drawable cache it shows no banner and does not force.
 * plans/261006-source-becomes-primary-fetch-and-banner.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class SourceSwitchFetchIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private val lat = 37.4168
    private val lon = -122.0889
    private val widgets = intArrayOf(301, 302)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor { _ -> }.setTaskExecutor(SynchronousExecutor()).build(),
        )
        WeatherDatabase.setIsTesting(true)
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
        ActiveLocationResolver.persist(context, lat, lon)
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, WeatherWidgetProvider::class.java) }
        val shadow = shadowOf(AppWidgetManager.getInstance(context))
        widgets.forEach { shadow.addBoundWidget(it, info) }
        val sm = WidgetStateManager(context)
        widgets.forEach { sm.clearTransientMessage(it) }
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun seedDrawableGoogleCache() = runBlocking {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayUtc = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        db.forecastDao().insertAll(
            listOf(
                ForecastEntity(
                    targetDate = todayUtc, dateOfPrediction = todayUtc, locationLat = lat, locationLon = lon,
                    highTemp = 80f, lowTemp = 60f, condition = "Clear", source = WeatherSource.GOOGLE_WEATHER.id,
                    fetchedAt = now, batchFetchedAt = now,
                ),
            ),
        )
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        db.hourlyForecastDao().insertAll(
            (0 until 24).map { h ->
                HourlyForecastEntity(
                    dateTime = start + h * 3_600_000L, locationLat = lat, locationLon = lon, temperature = 70f,
                    condition = "Clear", source = WeatherSource.GOOGLE_WEATHER.id, fetchedAt = now,
                )
            },
        )
    }

    @Test
    fun `no cache - banner on every widget and an expedited forced sync for that source`() = runBlocking {
        val started = SourceSwitchFetch.start(context, WeatherSource.GOOGLE_WEATHER, trigger = "test")

        assertTrue(started.bannerShown)
        val text = SourceSwitchFetch.message(context, WeatherSource.GOOGLE_WEATHER)
        val sm = WidgetStateManager(context)
        widgets.forEach { assertEquals(text, sm.getActiveTransientMessage(it)) }

        val input = started.request.workSpec.input
        assertTrue(input.getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, false))
        assertEquals(WeatherSource.GOOGLE_WEATHER.id, input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE))
        assertEquals(WeatherSource.GOOGLE_WEATHER.id, input.getString(WeatherWidgetWorker.KEY_SOURCE_SWITCH_ID))
        assertTrue(started.request.workSpec.expedited)
        val queued = WorkManager.getInstance(context).getWorkInfosByTag(SourceSwitchFetch.WORK_TAG).get(5, TimeUnit.SECONDS)
        assertEquals(listOf(started.request.id), queued.map { it.id })
    }

    @Test
    fun `drawable cache - no banner and no forced fetch`() = runBlocking {
        seedDrawableGoogleCache()
        val started = SourceSwitchFetch.start(context, WeatherSource.GOOGLE_WEATHER, trigger = "test")

        assertTrue(started.hadCache)
        assertFalse(started.bannerShown)
        assertFalse(started.request.workSpec.input.getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true))
        widgets.forEach { assertNull(WidgetStateManager(context).getActiveTransientMessage(it)) }
    }

    @Test
    fun `the sync's finish clears only its own banner`() = runBlocking {
        SourceSwitchFetch.start(context, WeatherSource.GOOGLE_WEATHER, trigger = "test")
        // A different message that took over one widget meanwhile is not ours to clear.
        WidgetStateManager(context).setTransientMessage(302, "Another notice", System.currentTimeMillis() + 60_000)

        val cleared = FetchBanner.clear(context, SourceSwitchFetch.message(context, WeatherSource.GOOGLE_WEATHER), widgets)

        assertEquals(1, cleared)
        assertNull(WidgetStateManager(context).getActiveTransientMessage(301))
        assertEquals("Another notice", WidgetStateManager(context).getActiveTransientMessage(302))
    }

    @Test
    fun `expedited only on API 31 and later`() {
        assertTrue(SourceSwitchFetch.buildRequest(WeatherSource.GOOGLE_WEATHER, forced = true, sdkInt = 31).workSpec.expedited)
        assertFalse(SourceSwitchFetch.buildRequest(WeatherSource.GOOGLE_WEATHER, forced = true, sdkInt = 30).workSpec.expedited)
    }
}
