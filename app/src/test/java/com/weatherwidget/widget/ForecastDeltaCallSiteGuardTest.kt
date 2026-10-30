package com.weatherwidget.widget

import com.weatherwidget.shared.graph.ForecastDeltaCallSites
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/** Android twin of desktop's guard: views show the resolver's delta and never work out their own. */
@Category(ShortDuration::class)
class ForecastDeltaCallSiteGuardTest {
    @Test
    fun `views read the resolved forecast delta`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        assertTrue("could not locate app sources from ${File(".").absolutePath}", root != null)
        val sources = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        assertTrue("expected TemperatureGraphRenderer.kt", "TemperatureGraphRenderer.kt" in sources)
        val offenders = ForecastDeltaCallSites.offenders(sources)
        assertTrue("Use the resolver's appliedDelta instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
