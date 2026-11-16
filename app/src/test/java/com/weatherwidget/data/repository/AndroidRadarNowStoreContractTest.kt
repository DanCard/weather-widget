package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.contract.RadarNowStoreContract
import com.weatherwidget.shared.observations.RadarNowSampler
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SharedPreferences half of [RadarNowStoreContract] (worker writes, renderer reads); the desktop half is `DesktopRadarNowStoreContractTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class AndroidRadarNowStoreContractTest : RadarNowStoreContract() {
    override fun open(): () -> RadarNowSampler.Store {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("radar_now", Context.MODE_PRIVATE).edit().clear().commit()
        return { AndroidRadarNowStore(context) }
    }
}
