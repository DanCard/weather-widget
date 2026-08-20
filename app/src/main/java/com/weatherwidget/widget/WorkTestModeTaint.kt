package com.weatherwidget.widget

import androidx.work.Data
import com.weatherwidget.data.local.WeatherDatabase

/**
 * Marks WorkManager requests that were enqueued while the process was in testing mode, so the
 * process that eventually *runs* them can drop them.
 *
 * Testing mode redirects the Room database and (through `SharedPreferencesUtil`) the preference
 * files. It does not redirect WorkManager: that queue is process-wide and persists to
 * `no_backup/androidx.work.workdb`, so a job an instrumented test enqueues survives the test process
 * and is executed later by a normal one — where `WeatherDatabase.isTestingMode()` is false and
 * [WeatherWidgetWorker]'s own guard therefore lets it through. The coordinates baked into its input
 * are the test fixture's, and the fetch lands in the production database.
 *
 * The flag has to travel inside the WorkSpec because nothing else durably connects "a test enqueued
 * this" to the process that runs it. See [WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING] and
 * plans/260820-backfill-test-leak-and-selfsustaining-loop.md.
 *
 * Deliberately stamped rather than suppressed at enqueue time: instrumented tests legitimately
 * assert that scheduling happened (e.g. `WidgetWorkSchedulerApi30IntegrationTest` drives a UI
 * repaint to `SUCCEEDED`), so the request must still exist and still succeed — it just must not do
 * any real work if it outlives its test.
 */
internal fun Data.Builder.tagTestModeEnqueue(): Data.Builder =
    apply {
        if (WeatherDatabase.isTestingMode()) {
            putBoolean(WeatherWidgetWorker.KEY_ENQUEUED_IN_TESTING, true)
        }
    }
