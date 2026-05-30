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
    ) = ForecastEvolutionRenderer.EvolutionPoint(
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
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, emptyList(), null, null, 700, 400)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `renderHighGraph empty input returns transparent bitmap`() {
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, emptyList(), emptyList(), null, null, 700, 400)
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
        val bitmap = ForecastEvolutionRenderer.renderLowGraph(context, emptyList(), points, null, null, 500, 300)
        assertEquals(500, bitmap.width)
        assertEquals(300, bitmap.height)
    }

    @Test
    fun `renderHighErrorGraph returns bitmap with correct dimensions`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighErrorGraph(context, points, emptyList(), 75f, 74f, 700, 400)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `renderLowErrorGraph returns bitmap with correct dimensions`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
        )
        val bitmap = ForecastEvolutionRenderer.renderLowErrorGraph(context, emptyList(), points, 55f, 54f, 700, 400)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `renderHighErrorGraph with no actuals returns transparent bitmap`() {
        val points = listOf(makePoint(1000L, 75f, 55f))
        val bitmap = ForecastEvolutionRenderer.renderHighErrorGraph(context, points, emptyList(), null, null, 700, 400)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(350, 200))
    }

    @Test
    fun `single point does not crash`() {
        val points = listOf(makePoint(1000L, 75f, 55f))
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, emptyList(), null, null, 700, 400)
        assertNotNull(bitmap)
        assertEquals(700, bitmap.width)
    }

    @Test
    fun `nws and meteo points together do not crash`() {
        val nwsPoints = listOf(
            makePoint(1000L, 75f, 55f, source = WeatherSource.NWS),
            makePoint(3000L, 74f, 54f, source = WeatherSource.NWS),
        )
        val meteoPoints = listOf(
            makePoint(2000L, 76f, 56f, source = WeatherSource.OPEN_METEO),
            makePoint(4000L, 73f, 53f, source = WeatherSource.OPEN_METEO),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, nwsPoints, meteoPoints, null, null, 700, 400)
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
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, emptyList(), 74f, 73f, 700, 400)
        assertEquals(700, bitmap.width)
    }

    @Test
    fun `null temps in points are handled gracefully`() {
        val points = listOf(
            makePoint(1000L, null, null),
            makePoint(2000L, 75f, null),
            makePoint(3000L, null, 55f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, emptyList(), null, null, 700, 400)
        assertNotNull(bitmap)
    }

    @Test
    fun `all temps identical produces valid bitmap`() {
        val points = listOf(
            makePoint(1000L, 72f, 52f),
            makePoint(2000L, 72f, 52f),
            makePoint(3000L, 72f, 52f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, emptyList(), null, null, 700, 400)
        assertEquals(700, bitmap.width)
        assertEquals(400, bitmap.height)
    }

    @Test
    fun `error graph with api actual bias does not crash`() {
        val points = listOf(
            makePoint(1000L, 75f, 55f),
            makePoint(2000L, 76f, 56f),
        )
        val bitmap = ForecastEvolutionRenderer.renderHighErrorGraph(context, points, emptyList(), 74f, 73f, 700, 400)
        assertEquals(700, bitmap.width)
    }

    @Test
    fun `large number of points does not crash`() {
        val points = (0..100).map { i ->
            makePoint(fetchedAt = i * 3600_000L, highTemp = 70f + i * 0.1f, lowTemp = 50f + i * 0.1f)
        }
        val bitmap = ForecastEvolutionRenderer.renderHighGraph(context, points, emptyList(), null, null, 700, 400)
        assertEquals(700, bitmap.width)
    }
}
