package com.weatherwidget.architecture

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/** Keeps the daily graph renderer as an ordered facade over cohesive collaborators. */
@Category(ShortDuration::class)
class DailyForecastGraphRendererArchitectureTest {
    private val sourceRoot by lazy(::findMainSourceRoot)
    private val facade by lazy {
        sourceRoot.resolve("com/weatherwidget/widget/DailyForecastGraphRenderer.kt").readText()
    }

    @Test
    fun `daily graph facade delegates extracted responsibilities`() {
        assertTrue(
            "DailyForecastGraphRenderer should remain at or below the 500-line facade target",
            facade.lineSequence().count() <= 500,
        )
        listOf(
            "DailyGraphInputNormalizer.normalize(",
            "DailyGraphLayoutResolver.resolve(",
            "DailyGraphPaintCache.get(",
            "DailyBarRenderer.drawDayBars(",
            "DailyColumnRenderer.draw(",
        ).forEach { expectedDelegate ->
            assertTrue(
                "DailyForecastGraphRenderer must delegate through $expectedDelegate",
                expectedDelegate in facade,
            )
        }
    }

    @Test
    fun `daily graph facade does not reacquire extracted implementation methods`() {
        listOf(
            "fun computeLayout(",
            "fun getPaintSet(",
            "fun drawDayColumn(",
            "fun drawWeatherIcon(",
            "fun drawTempLabel(",
            "fun formatTempLabel(",
        ).forEach { forbiddenImplementation ->
            assertFalse(
                "Extracted implementation returned to DailyForecastGraphRenderer: " +
                    forbiddenImplementation,
                forbiddenImplementation in facade,
            )
        }
    }

    @Test
    fun `daily collaborators do not call facade implementation helpers`() {
        listOf(
            "DailyBarRenderer.kt",
            "DailyHighLabelPlanner.kt",
            "DailyForecastRainLabelRenderer.kt",
        ).forEach { fileName ->
            val source =
                sourceRoot.resolve("com/weatherwidget/widget/$fileName").readText()
            listOf(
                "DailyForecastGraphRenderer.drawTempLabel",
                "DailyForecastGraphRenderer.formatTempLabel",
                "DailyForecastGraphRenderer.tempLabelDrawScale",
                "DailyForecastGraphRenderer.resolveLowLabelBaseline",
            ).forEach { forbiddenCall ->
                assertFalse("$fileName must not call $forbiddenCall", forbiddenCall in source)
            }
        }
    }

    /** Unit tests normally use the module directory, but CI entry points vary. */
    private fun findMainSourceRoot(): File {
        val candidates =
            listOf(
                File("src/main/java"),
                File("app/src/main/java"),
            )
        return requireNotNull(candidates.firstOrNull { it.isDirectory }) {
            "Could not locate app main source root from ${File(".").absolutePath}"
        }
    }
}
