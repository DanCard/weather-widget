package com.weatherwidget.desktop

import com.weatherwidget.shared.util.UserRefreshCallSites
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Desktop twin of Android's `UserRefreshScopeGuardTest`: every user refresh (`userRefresh = true`)
 * starts through the shared `UserRefreshRunner`, so no window's scope can cancel it. July's bug was
 * Refresh on the Settings window's `rememberCoroutineScope`; the fix then was desktop-only and Android
 * repeated it (plans/261013-observations-refresh-survives-closing-the-screen.md).
 */
@Category(ShortDuration::class)
class UserRefreshScopeGuardTest {
    private val userRefreshCall = Regex("""userRefresh\s*=\s*true""")

    @Test
    fun `user refreshes run through the UserRefreshRunner`() {
        val root = listOf(
            File("src/main/kotlin/com/weatherwidget/desktop"),
            File("desktop/src/main/kotlin/com/weatherwidget/desktop"),
        ).firstOrNull { it.isDirectory }
        assertTrue("could not locate desktop sources from ${File(".").absolutePath}", root != null)

        var scanned = 0
        val offenders = mutableListOf<String>()
        root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            scanned += UserRefreshCallSites.count(text, userRefreshCall)
            UserRefreshCallSites.uncovered(text, userRefreshCall).forEach { line ->
                offenders += "${file.name}:$line: ${text.lines()[line - 1].trim()}"
            }
        }

        // requestFullRefresh + requestSourceRefresh; a rename must not make this pass vacuously.
        assertTrue("expected the Refresh call sites, found $scanned", scanned >= 2)
        if (offenders.isNotEmpty()) {
            fail("User refresh(es) outside refreshRunner.start { … }:\n" + offenders.joinToString("\n"))
        }
    }

    /**
     * The window process exits 400 ms after its last window closes, so it fetches nothing itself: every
     * fetch that stores data is a [FetchRequest] the daemon runs (plans/261112-desktop-fetches-run-in-daemon.md).
     * The window may still read (`loadCached`) and show display-only images (radar, rain loop).
     */
    @Test
    fun `the window process runs no fetch itself`() {
        val file = listOf(
            File("src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt"),
            File("desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt"),
        ).firstOrNull { it.isFile }
        assertTrue("could not locate DesktopUiApplication.kt from ${File(".").absolutePath}", file != null)
        val text = file!!.readText()
        val fetchCalls = Regex(
            """\.(refresh|refreshWithOutcome|refreshObservations|refreshObservationWindow|extendHourlyFor|ensureHistory)\(""",
        )
        val offenders = text.lines().withIndex()
            .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && fetchCalls.containsMatchIn(line) }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }
        // One service, behind the repository the window reads its cache through; no other.
        assertEquals("DesktopWeatherService constructions", 1, Regex("""DesktopWeatherService\(""").findAll(text).count())
        // Seven kinds of request; a rename must not make this pass vacuously.
        assertTrue("expected the daemon fetch requests", Regex("""FetchRequest\(""").findAll(text).count() >= 7)
        if (offenders.isNotEmpty()) fail("Fetch(es) in the window process:\n" + offenders.joinToString("\n"))
    }
}
