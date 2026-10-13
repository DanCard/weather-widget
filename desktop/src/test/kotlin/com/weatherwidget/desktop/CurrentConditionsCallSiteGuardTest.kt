package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.CurrentConditionsCallSites
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/** Desktop twin of Android's guard: model-feed current conditions go through `CurrentConditionsFetcher`. */
@Category(ShortDuration::class)
class CurrentConditionsCallSiteGuardTest {
    @Test
    fun `model-feed current conditions go through the shared fetcher`() {
        val root = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).firstOrNull { it.isDirectory }
        assertTrue("could not locate desktop sources from ${File(".").absolutePath}", root != null)
        val sources = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        assertTrue("expected DesktopWeatherService.kt", "DesktopWeatherService.kt" in sources)
        val offenders = CurrentConditionsCallSites.offenders(sources)
        assertTrue("Call CurrentConditionsFetcher instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
