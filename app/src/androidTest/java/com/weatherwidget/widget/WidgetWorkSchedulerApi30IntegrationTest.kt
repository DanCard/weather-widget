package com.weatherwidget.widget

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weatherwidget.testutil.IsolatedIntegrationTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs the production zero-delay UI-work path on Android 11, where expedited CoroutineWorker work
 * otherwise asks for foreground information and crashes before [WeatherWidgetWorker.doWork].
 */
@RunWith(AndroidJUnit4::class)
class WidgetWorkSchedulerApi30IntegrationTest : IsolatedIntegrationTest("widget_work_api30") {
    private var requestId: UUID? = null

    @After
    override fun cleanup() {
        requestId?.let { id ->
            WorkManager.getInstance(context).cancelWorkById(id).result.get(5, TimeUnit.SECONDS)
        }
        super.cleanup()
    }

    @Test
    fun zeroDelayUiRepaint_succeedsWithoutForegroundInfo() {
        assumeTrue("This regression must exercise Android 11", Build.VERSION.SDK_INT == 30)

        val request = WidgetWorkScheduler.enqueueUiRepaint(context, reason = "api30_foreground_regression")
        requestId = request.id

        val workManager = WorkManager.getInstance(context)
        val deadlineMs = SystemClock.elapsedRealtime() + WORK_TIMEOUT_MS
        var latest: WorkInfo? = null
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            latest = workManager.getWorkInfoById(request.id).get(2, TimeUnit.SECONDS)
            when (latest?.state) {
                WorkInfo.State.SUCCEEDED -> break
                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED,
                -> fail("UI repaint work ended in ${latest.state}")
                else -> SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }

        assertEquals(
            "UI repaint did not succeed within ${WORK_TIMEOUT_MS}ms; latest=$latest",
            WorkInfo.State.SUCCEEDED,
            latest?.state,
        )
    }

    private companion object {
        const val WORK_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
