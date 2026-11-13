package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.SourceRetryHostContract
import com.weatherwidget.shared.util.SourceRetry
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android half of [SourceRetryHostContract] (widget-state prefs); the desktop half is `DesktopSourceRetryHostContractTest`. */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class AndroidSourceRetryHostContractTest : SourceRetryHostContract() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var stateManager: WidgetStateManager
    private val wakes = mutableListOf<Pair<String, Long>>()

    override fun open(displayed: String): SourceRetry.Host {
        stateManager = WidgetStateManager(context)
        WeatherSource.entries.forEach { stateManager.recordSourceFetchSuccess(it) }
        // No widget is bound under Robolectric, so the displayed source is the primary (first visible) one.
        val shown = WeatherSource.fromId(displayed)
        stateManager.setVisibleSourcesOrder(listOf(shown) + WeatherSource.entries.filter { it != shown && it.id != "NWS" }.take(1))
        return AndroidSourceRetryHost(
            context, stateManager, TestDatabase.create().appLogDao(),
            power = { true to true },
            enqueueRetry = { sourceId, delayMs -> wakes += sourceId to delayMs },
        )
    }

    override suspend fun recordSuccess(sourceId: String) = stateManager.recordSourceFetchSuccess(WeatherSource.fromId(sourceId))

    override fun wakes() = wakes.toList()
}
