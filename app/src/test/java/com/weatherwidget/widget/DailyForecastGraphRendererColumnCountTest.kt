package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyForecastGraphRendererColumnCountTest {

    @Test
    fun `renderGraph spaces bars using visible widget columns when some days are missing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val day = DailyForecastGraphRenderer.DayData(
            date = LocalDate.of(2026, 3, 26),
            label = "Thu",
            high = 70f,
            low = 50f,
            columnIndex = 7,
        )
        val draws = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()

        DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = listOf(day),
            widthPx = 900,
            heightPx = 300,
            numColumns = 9,
            onBarDrawn = draws::add,
        )

        assertTrue("expected at least one bar draw callback", draws.isNotEmpty())
        assertEquals(750f, draws.first().centerX, 1.5f)
    }
}
