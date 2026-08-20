package com.weatherwidget.widget

import androidx.work.Data
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Testing mode redirects the database and the preference files, but not WorkManager — its queue is
 * process-wide and persists to `no_backup/androidx.work.workdb`. A job an instrumented test enqueues
 * therefore outlives the test process, and the normal process that later runs it has the flag off,
 * so [WeatherWidgetWorker]'s own testing-mode guard waves it through and it fetches for real against
 * the production database.
 *
 * These lock the taint that closes that hole: it is stamped at ENQUEUE time and must survive in the
 * WorkSpec input, which is the only durable link between the two processes.
 *
 * See plans/260820-backfill-test-leak-and-selfsustaining-loop.md.
 */
@Category(ShortDuration::class)
class WorkTestModeTaintTest {

    @After
    fun resetTestingMode() {
        WeatherDatabase.setIsTesting(false)
    }

    private fun build(): Data = Data.Builder().tagTestModeEnqueue().build()

    @Test
    fun `input built under testing mode carries the taint`() {
        WeatherDatabase.setIsTesting(true)
        assertTrue(build().getBoolean(WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING, false))
    }

    @Test
    fun `input built normally carries no taint`() {
        WeatherDatabase.setIsTesting(false)
        assertFalse(build().getBoolean(WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING, false))
    }

    /**
     * The point of the whole mechanism: the flag is read back in a process where testing mode is
     * OFF. If it were derived from the live flag instead of the WorkSpec, this would read false and
     * the job would run for real.
     */
    @Test
    fun `the taint outlives the testing mode that set it`() {
        WeatherDatabase.setIsTesting(true)
        val enqueued = build()
        WeatherDatabase.setIsTesting(false)

        assertFalse("precondition: the running process is a normal one", WeatherDatabase.isTestingMode())
        assertTrue(
            "work enqueued by a test must still be identifiable after the test process is gone",
            enqueued.getBoolean(WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING, false),
        )
    }

    @Test
    fun `the taint does not disturb the rest of the input`() {
        WeatherDatabase.setIsTesting(true)
        val data = Data.Builder()
            .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
            .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, 37.417)
            .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, -122.089)
            .tagTestModeEnqueue()
            .build()

        assertTrue(data.getBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, false))
        assertTrue(data.getBoolean(WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING, false))
        val input = WorkInput.from(data)
        assertTrue(input.observationBackfillMode)
        assertTrue(input.backfillLat == 37.417 && input.backfillLon == -122.089)
    }
}
