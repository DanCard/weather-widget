package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The prune runs in its own job (never inside a fetch) and records where the next pass starts. */
@Category(LongDuration::class)
class HistoryPruneWorkerTest : RobolectricTest() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
    }

    @Test
    fun `the prune waits for charging with the device idle`() {
        val constraints = HistoryPruneWorker.request().workSpec.constraints
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresDeviceIdle())
    }

    @Test
    fun `worker prunes a deep stack and records its start time`() = runBlocking {
        val dao = WeatherDatabase.getDatabase(context).hourlyForecastHistoryDao()
        val hour = 1_790_640_000_000L
        dao.insertAll(
            (1..30).map { k ->
                HourlyForecastHistoryEntity(
                    dateTime = hour, locationLat = 52.2334, locationLon = 20.9703, temperature = 60f,
                    condition = "Clear", source = "OPEN_METEO", timestampToGroupPredictions = hour - k * 3_600_000L,
                    fetchedAt = hour - k * 3_600_000L + 60_000L,
                )
            },
        )
        val before = System.currentTimeMillis()

        val result = TestListenableWorkerBuilder<HistoryPruneWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val left = dao.getAllInDateTimeRange(Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue("30 copies with no fields -> at most newest + prior-day survive: ${left.size}", left.size in 1..3)
        val recorded = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
            .getLong("history_prune_last_completed_start_ms", 0L)
        assertTrue(recorded >= before)
    }
}
