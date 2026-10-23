package com.weatherwidget.widget

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.shared.util.RefreshNotice.Kind
import com.weatherwidget.shared.util.RefreshNotice.NotQueued
import com.weatherwidget.shared.util.RefreshNotice.Queued
import com.weatherwidget.shared.util.RefreshNotice.Reason
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.handlers.RefreshScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * [RefreshToast] (text, gate, log row) and [RefreshScheduler.refreshIfStale]'s outcome, against a real
 * Room DB and the WorkManager test driver. plans/261015-refresh-queued-toast-all-surfaces.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class RefreshToastRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WeatherDatabase
    private var clockMs = 100_000L

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor { _ -> }.setTaskExecutor(SynchronousExecutor()).build(),
        )
        WeatherDatabase.setIsTesting(true)
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
        RefreshToast.resetForTesting()
        RefreshToast.clock = { clockMs }
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
        context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE).edit().clear().commit()
        SourceQuotaBlocks.reset()
    }

    @After
    fun teardown() {
        RefreshToast.resetForTesting()
        SourceQuotaBlocks.reset()
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun show(notice: RefreshNotice, origin: String = "test") = runBlocking {
        RefreshToast.show(context, origin, notice)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun toastRows() = runBlocking { db.appLogDao().getLogsByTag("REFRESH_TOAST", 50) }

    @Test
    fun `each notice maps to its sentence`() {
        assertEquals("Forecast refresh queued", RefreshToast.text(context, Queued(Kind.FORECAST)))
        assertEquals("Observations refresh queued", RefreshToast.text(context, Queued(Kind.OBSERVATIONS)))
        assertEquals("Forecast and observations refresh queued", RefreshToast.text(context, Queued(Kind.BOTH)))
        assertEquals(
            "NWS forecast up to date · 2h 10m ago",
            RefreshToast.text(context, NotQueued(Reason.UP_TO_DATE, "NWS", ageMs = 130 * 60_000L)),
        )
        assertEquals(
            "NWS observations up to date · 6m ago",
            RefreshToast.text(context, NotQueued(Reason.UP_TO_DATE, "NWS", Kind.OBSERVATIONS, ageMs = 6 * 60_000L)),
        )
        assertEquals("Refresh already queued", RefreshToast.text(context, NotQueued(Reason.ALREADY_QUEUED)))
        assertEquals("Refresh already in progress", RefreshToast.text(context, NotQueued(Reason.IN_PROGRESS)))
        assertTrue(
            RefreshToast.text(context, NotQueued(Reason.QUOTA, "Google", untilMs = 0L))
                .startsWith("Google limit reached · next refresh after "),
        )
        assertEquals(
            context.getString(com.weatherwidget.R.string.obs_no_location_to_refresh),
            RefreshToast.text(context, NotQueued(Reason.NO_LOCATION)),
        )
    }

    @Test
    fun `a notice shows a long toast and writes a row`() {
        show(Queued(Kind.FORECAST), origin = "widget_toggle_api")

        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("Forecast refresh queued", ShadowToast.getTextOfLatestToast())
        assertEquals(android.widget.Toast.LENGTH_LONG, ShadowToast.getLatestToast().duration)
        val row = toastRows().single()
        assertEquals("origin=widget_toggle_api queued=FORECAST shown", row.message)
    }

    @Test
    fun `a second notice within four seconds is suppressed but logged`() {
        show(Queued(Kind.FORECAST))
        clockMs += 500L
        show(NotQueued(Reason.UP_TO_DATE, "NWS", ageMs = 60_000L))
        clockMs += RefreshNotice.DURATION_MS
        show(Queued(Kind.OBSERVATIONS))

        assertEquals(2, ShadowToast.shownToastCount())
        assertEquals("Observations refresh queued", ShadowToast.getTextOfLatestToast())
        val messages = toastRows().map { it.message }
        assertEquals(1, messages.count { it.endsWith("suppressed") })
        assertEquals(2, messages.count { it.endsWith("shown") })
    }

    @Test
    fun `refreshIfStale - fresh data is up to date with the source and age`() = runBlocking {
        val now = System.currentTimeMillis()
        val notice = RefreshScheduler.refreshIfStale(
            context, now - 5 * 60_000L, "toggle_api", db.appLogDao(), WeatherSource.NWS.id,
        ) as NotQueued

        assertEquals(Reason.UP_TO_DATE, notice.reason)
        assertEquals("NWS", notice.sourceName)
        assertTrue("age ${notice.ageMs}", notice.ageMs!! in 5 * 60_000L..6 * 60_000L)
    }

    @Test
    fun `refreshIfStale - stale data queues the forecast and the current temperature`() = runBlocking {
        val notice = RefreshScheduler.refreshIfStale(context, 0L, "toggle_api", db.appLogDao(), WeatherSource.NWS.id)

        assertEquals(Queued(Kind.BOTH), notice)
    }

    @Test
    fun `refreshIfStale - a quota-blocked source names when the block ends`() = runBlocking {
        val until = System.currentTimeMillis() + 3_600_000L
        com.weatherwidget.data.model.ForecastProduct.entries.forEach {
            SourceQuotaBlocks.block(WeatherSource.GOOGLE_WEATHER.id, it, until)
        }

        val notice = RefreshScheduler.refreshIfStale(context, 0L, "toggle_api", db.appLogDao(), WeatherSource.GOOGLE_WEATHER.id)

        assertEquals(NotQueued(Reason.QUOTA, WeatherSource.GOOGLE_WEATHER.shortDisplayName, untilMs = until), notice)
    }

    @Test
    fun `refreshIfStale - a repeat inside the debounce is already queued`() = runBlocking {
        RefreshScheduler.refreshIfStale(context, 0L, "toggle_api", db.appLogDao(), WeatherSource.NWS.id)
        val second = RefreshScheduler.refreshIfStale(context, 0L, "toggle_api", db.appLogDao(), WeatherSource.NWS.id)

        assertEquals(NotQueued(Reason.ALREADY_QUEUED), second)
    }

    @Test
    fun `refreshIfStale - a sync already forced for the source absorbs the forecast, only observations are queued`() = runBlocking {
        WidgetWorkScheduler.enqueueForcedSync(
            context, "earlier", ExistingWorkPolicy.KEEP, initialDelayMs = 3_600_000L, targetSourceId = WeatherSource.NWS.id,
        )

        val notice = RefreshScheduler.refreshIfStale(context, 0L, "toggle_api", db.appLogDao(), WeatherSource.NWS.id)

        assertEquals(Queued(Kind.OBSERVATIONS), notice)
    }

    @Test
    fun `refreshIfStale - a sync waiting for another source does not absorb this one's forecast`() = runBlocking {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetWorkScheduler.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>().setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS).build(),
        ).result.get()

        val notice = RefreshScheduler.refreshIfStale(context, 0L, "toggle_api", db.appLogDao(), WeatherSource.NWS.id)

        assertEquals(Queued(Kind.BOTH), notice)
    }

    @Test
    fun `enqueueForcedRefresh reports a waiting sync and never toasts by itself`() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetWorkScheduler.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>().setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS).build(),
        ).result.get()

        // The automatic callers (viewing refresh, WeatherAPI backfill) go straight here.
        val notice = RefreshScheduler.enqueueForcedRefresh(context, reason = "viewing_forecast_due")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(NotQueued(Reason.ALREADY_QUEUED), notice)
        assertEquals(0, ShadowToast.shownToastCount())
        assertTrue(toastRows().isEmpty())
    }

    @Test
    fun `a history refresh's ending replaces the queued notice and writes a row`() {
        // It bypassed the throttle and left no REFRESH_TOAST row (desktop's showOutcome had both).
        show(Queued(Kind.FORECAST), origin = "history_refresh")
        clockMs += 500L
        runBlocking {
            RefreshToast.showOutcome(context, "history_refresh", com.weatherwidget.shared.util.RefreshOutcome.Refreshed, "Refreshed NWS")
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Refreshed NWS", ShadowToast.getTextOfLatestToast())
        assertTrue(toastRows().any { it.message == "origin=history_refresh done=Refreshed shown" })

        // The ending restarted the window: a notice right after it is suppressed.
        clockMs += RefreshNotice.DURATION_MS - 1
        show(Queued(Kind.FORECAST), origin = "after")
        assertTrue(toastRows().any { it.message == "origin=after queued=FORECAST suppressed" })
    }
}
