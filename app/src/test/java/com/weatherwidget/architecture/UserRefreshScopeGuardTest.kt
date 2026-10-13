package com.weatherwidget.architecture

import com.weatherwidget.shared.util.UserRefreshCallSites
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * A fetch a screen starts must run through `UserRefreshRunner`, so closing the screen cancels only
 * its waiting. On the Fold 4 (2026-10-13) Observations Refresh ran on `lifecycleScope`; the user
 * closed the screen 5 s in and the NWS fetch was cancelled with nothing stored. Desktop had the same
 * bug in July. The rule and the "inside the runner" scan are shared (`:shared` `UserRefreshRunner`,
 * `UserRefreshCallSites`); desktop has the twin of this test
 * (plans/261013-observations-refresh-survives-closing-the-screen.md).
 *
 * If this failed on your new code: wrap the fetch in `userRefreshRunner.start(origin) { … }` and
 * `await()` it from the screen.
 */
@Category(ShortDuration::class)
class UserRefreshScopeGuardTest {
    private val fetchCall = Regex("""refreshCurrentTemperature\(|fetchSourceOnDemand\(|\.fetchObservations\(""")

    @Test
    fun screenFetchesRunThroughTheUserRefreshRunner() {
        val uiRoot = listOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("com/weatherwidget/ui") }
            .firstOrNull { it.isDirectory }
        assertTrue("could not locate app ui sources from ${File(".").absolutePath}", uiRoot != null)

        var scanned = 0
        val offenders = mutableListOf<String>()
        uiRoot!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            scanned += UserRefreshCallSites.count(text, fetchCall)
            UserRefreshCallSites.uncovered(text, fetchCall).forEach { line ->
                offenders += "${file.name}:$line: ${text.lines()[line - 1].trim()}"
            }
        }

        // Observations (Refresh + stale repair) and History each fetch; a rename must not make this pass vacuously.
        assertTrue("expected the screens' fetch calls, found $scanned", scanned >= 4)
        if (offenders.isNotEmpty()) {
            fail(
                "Screen fetch call(s) outside UserRefreshRunner.start { … }:\n" + offenders.joinToString("\n") +
                    "\n\nOn lifecycleScope, closing the screen cancels the fetch and stores nothing.",
            )
        }
    }
}
