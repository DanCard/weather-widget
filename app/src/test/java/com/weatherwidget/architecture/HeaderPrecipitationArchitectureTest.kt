package com.weatherwidget.architecture

import com.weatherwidget.test.category.ShortDuration
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HeaderPrecipitationArchitectureTest {
    private val sourceRoot by lazy(::findMainSourceRoot)

    @Test
    fun `daily graph consumes resolved header precipitation state`() {
        val source = sourceRoot
            .resolve("com/weatherwidget/widget/handlers/DailyGraphRenderer.kt")
            .readText()

        assertTrue("Daily graph must consume the resolved header text size", "headerState.precipTextSizeDp" in source)
        assertFalse("Daily graph must not resolve precipitation again", "HeaderPrecipCalculator" in source)
    }

    @Test
    fun `android adapter does not expose obsolete eight hour contracts`() {
        val source = sourceRoot
            .resolve("com/weatherwidget/util/HeaderPrecipCalculator.kt")
            .readText()

        assertFalse("Obsolete eight-hour header APIs must not return", "Next8Hour" in source)
    }

    private fun findMainSourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return requireNotNull(candidates.firstOrNull { it.isDirectory }) {
            "Could not locate app main source root from ${File(".").absolutePath}"
        }
    }
}
