package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.contract.DailyRainLabelHostContract
import com.weatherwidget.shared.graph.DailyRainLabelLayout
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The widget's Paint host; NATIVE graphics so text measures with a real font engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(LongDuration::class)
class AndroidDailyRainLabelHostContractTest : DailyRainLabelHostContract() {
    override fun host(): DailyRainLabelLayout.Host {
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        val days = listOf(
            DailyForecastGraphRenderer.DayData(date = LocalDate.of(2026, 10, 27), label = "Tue", solidLineHigh = 66f, solidLineLow = 48f),
            DailyForecastGraphRenderer.DayData(date = LocalDate.of(2026, 10, 28), label = "Wed", solidLineHigh = 70f, solidLineLow = 46f),
        )
        val layout = DailyGraphLayoutResolver.resolve(
            days = days, widthPx = 600, heightPx = 400, columns = 2, bitmapScale = 1f, density = density, useCelsius = false,
        )
        return DailyForecastRainLabelRenderer.rainLabelHost(DailyGraphPaintCache.get(layout))
    }
}
