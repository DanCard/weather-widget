package com.weatherwidget.widget

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ForecastEvolutionRendererTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun makePoint(
        fetchedAt: Long,
        highTemp: Float?,
        lowTemp: Float?,
        source: WeatherSource = WeatherSource.NWS,
        daysAhead: Int = 3,
    ) = EvolutionPoint(
        forecastDate = "2026-05-01",
        fetchedAt = fetchedAt,
        daysAhead = daysAhead,
        highTemp = highTemp,
        lowTemp = lowTemp,
        source = source,
    )

    @Test
    fun `renderHighGraph returns bitmap with correct dimensions`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
            makePoint(3000L, 74f, 54f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, null, null, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `renderHighGraph empty input returns transparent bitmap`() {
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, emptyList(), null, null, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(350, 200))
    }

    @Test
    fun `renderLowGraph returns bitmap with correct dimensions`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
        )
        val bitmap = ForecastEvolutionRenderer.renderLowGraph(context, points, null, null, 500, 300, useCelsius = false)
        assertEquals(500, bitmap.width)
        assertEquals(300, bitmap.height)
    }

    @Test
    fun `renderHighErrorGraph returns bitmap with correct dimensions`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighErrorGraph(context, points, 75f, 74f, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `renderLowErrorGraph returns bitmap with correct dimensions`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
        )
        val bitmap = ForecastEvolutionRenderer.renderLowErrorGraph(context, points, 55f, 54f, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `renderHighErrorGraph with no actuals returns transparent bitmap`() {
        val points = listOf(makePoint(1000L, 75f, 55f))
        val bitmap = ForecastEvolutionRenderer.renderHighErrorGraph(context, points, null, null, 700, 400, useCelsius = false)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(350, 200))
    }

    @Test
    fun `single point does not crash`() {
        val points = listOf(makePoint(1000L, 75f, 55f))
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, null, null, 700, 400, useCelsius = false)
        assertNotNull(bitmap)
        assertEquals(700, bitmap.width)
    }

    @Test
    fun `silurian-only series renders in evolution mode (regression)`() {
        // Before the single-series fix, a hardcoded source allow-list dropped Silurian, so its
        // forecast history rendered blank. The renderer must now draw whatever single source it is given.
        val points = listOf(
            makePoint(1000L, 75f, 55f, source = WeatherSource.SILURIAN),
            makePoint(2000L, 76f, 56f, source = WeatherSource.SILURIAN),
            makePoint(3000L, 74f, 54f, source = WeatherSource.SILURIAN),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, null, null, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `silurian-only series renders in error mode (regression)`() {
        // Error mode previously matched only OPEN_METEO/NWS and hid every other non-NWS API.
        val points = listOf(
            makePoint(1000L, 75f, 55f, source = WeatherSource.SILURIAN),
            makePoint(2000L, 76f, 56f, source = WeatherSource.SILURIAN),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighErrorGraph(context, points, 74f, 73f, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `actual value lines do not crash`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
            makePoint(3000L, 74f, 54f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, 74f, 73f, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
    }

    @Test
    fun `null temps in points are handled gracefully`() {
        val points = listOf(
            makePoint(1000L, null, null),
            makePoint(2000L, 75f, null),
            makePoint(3000L, null, 55f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, null, null, 700, 400, useCelsius = false)
        assertNotNull(bitmap)
    }

    @Test
    fun `all temps identical produces valid bitmap`() {
        val points = listOf(
            makePoint(1000L, 72f, 52f),
            makePoint(2000L, 72f, 52f),
            makePoint(3000L, 72f, 52f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, null, null, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `error graph with api actual bias does not crash`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighErrorGraph(context, points, 74f, 73f, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
    }

    @Test
    fun `large number of points does not crash`() {
        val points = (0..100).map { i ->
            makePoint(fetchedAt = i * 3600_000L, highTemp = 70f + i * 0.1f, lowTemp = 50f + i * 0.1f)
        }
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, null, null, 700, 400, useCelsius = false)
        assertEquals(700, bitmap.width)
    }
}
