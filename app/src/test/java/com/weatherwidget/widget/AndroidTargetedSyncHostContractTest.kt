package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.weatherwidget.shared.contract.TargetedForecastSyncHostContract
import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.test.category.MediumDuration
import io.mockk.mockk
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WorkManager half of [TargetedForecastSyncHostContract]; the desktop half is `DesktopTargetedSyncHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class AndroidTargetedSyncHostContractTest : TargetedForecastSyncHostContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun workManager() = WorkManager.getInstance(context)

    override fun open(): TargetedForecastSyncRun.Host {
        workManager().cancelUniqueWork(WidgetWorkScheduler.WORK_NAME_POST_SYNC_ACTUALS).result.get()
        workManager().pruneWork().result.get()
        return AndroidTargetedSyncHost(
            context, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), 37.422 to -122.084,
        )
    }

    override fun armedTails(): Int =
        workManager().getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_POST_SYNC_ACTUALS).get()
            .count { !it.state.isFinished }

    override fun close() {
        workManager().cancelUniqueWork(WidgetWorkScheduler.WORK_NAME_POST_SYNC_ACTUALS).result.get()
    }
}
