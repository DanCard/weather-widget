package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.contract.PreUnplugFetchHostContract
import com.weatherwidget.shared.util.PreUnplugFetchRunner
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prefs half of [PreUnplugFetchHostContract]; the desktop half is `DesktopPreUnplugHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class PrefsPreUnplugHostContractTest : PreUnplugFetchHostContract() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    override fun open(nowMs: Long): PreUnplugFetchRunner.Host {
        SharedPreferencesUtil.getPrefs(context, PrefsChargeHistoryStore.PREFS_NAME).edit().clear().commit()
        SharedPreferencesUtil.getPrefs(context, PrefsChargeHistoryStore.LEGACY_PRE_WAKE_PREFS).edit().clear().commit()
        return reopen(nowMs)
    }

    override fun reopen(nowMs: Long): PreUnplugFetchRunner.Host =
        PreUnplugFetchScheduler.AndroidHost(context, clock = { nowMs }, alarm = { null })
}
