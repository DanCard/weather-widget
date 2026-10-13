package com.weatherwidget.architecture

import com.weatherwidget.shared.observations.CurrentConditionsCallSites
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Model-feed current conditions are fetched by `:shared` `CurrentConditionsFetcher` only; desktop has
 * the twin of this test (plans/261013-shared-current-conditions-fetch.md). Before, Android sampled
 * five points per feed and desktop one, under different station names.
 */
@Category(ShortDuration::class)
class CurrentConditionsCallSiteGuardTest {
    @Test
    fun modelFeedCurrentConditionsGoThroughTheSharedFetcher() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        assertTrue("could not locate app main sources from ${File(".").absolutePath}", root != null)
        val sources = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        assertTrue("expected the full refresh's Tomorrow.io history call", "ForecastFetchCoordinator.kt" in sources)
        val offenders = CurrentConditionsCallSites.offenders(sources)
        assertTrue("Call CurrentConditionsFetcher instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
