package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.ForecastDeltaCallSites
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/** Desktop twin of Android's guard: views show the resolver's delta and never work out their own. */
@Category(ShortDuration::class)
class ForecastDeltaCallSiteGuardTest {
    @Test
    fun `the scan recognises the calculation it forbids`() {
        val offenders = ForecastDeltaCallSites.offenders(
            mapOf("Sample.kt" to "    lastActualPoint.actualTemp!! - lastActualPoint.forecastTemp\n"),
        )
        assertEquals(1, offenders.size)
    }

    @Test
    fun `views read the resolved forecast delta`() {
        val root = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).firstOrNull { it.isDirectory }
        assertTrue("could not locate desktop sources from ${File(".").absolutePath}", root != null)
        val sources = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        assertTrue("expected TemperatureGraph.kt", "TemperatureGraph.kt" in sources)
        val offenders = ForecastDeltaCallSites.offenders(sources)
        assertTrue("Use the resolver's appliedDelta instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
