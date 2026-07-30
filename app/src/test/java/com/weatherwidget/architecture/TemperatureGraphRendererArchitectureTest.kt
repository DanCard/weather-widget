package com.weatherwidget.architecture

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Keeps TemperatureGraphRenderer as the ordered facade established by the 2026-07-29 extraction.
 *
 * Rendering details belong to the focused collaborators so correctness fixes can be tested without
 * rebuilding a monolithic, mutable render context.
 */
@Category(ShortDuration::class)
class TemperatureGraphRendererArchitectureTest {
    private val source by lazy {
        findMainSourceRoot()
            .resolve("com/weatherwidget/widget/TemperatureGraphRenderer.kt")
            .readText()
    }

    @Test
    fun `temperature graph facade delegates extracted responsibilities`() {
        assertTrue(
            "TemperatureGraphRenderer should remain at or below the 350-line facade target",
            source.lineSequence().count() <= 350,
        )
        listOf(
            "TemperatureGraphSeriesResolver.resolve(",
            "TemperatureGraphSeriesRenderer.draw(",
            "TemperatureFetchDotRenderer.plan(",
            "TemperatureGraphAnnotationRenderer.placeTemperatureLabels(",
        ).forEach { expectedDelegate ->
            assertTrue(
                "TemperatureGraphRenderer must delegate through $expectedDelegate",
                expectedDelegate in source,
            )
        }
    }

    @Test
    fun `temperature graph facade does not reacquire extracted implementation methods`() {
        listOf(
            "private fun drawFillAndCurves(",
            "private fun drawFetchDot(",
            "private fun placeTemperatureLabels(",
            "private fun placeDayLabels(",
            "data class RenderContext",
        ).forEach { forbiddenImplementation ->
            assertFalse(
                "Extracted implementation returned to TemperatureGraphRenderer: " +
                    forbiddenImplementation,
                forbiddenImplementation in source,
            )
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
