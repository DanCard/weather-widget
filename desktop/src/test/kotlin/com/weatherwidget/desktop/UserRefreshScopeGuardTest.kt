package com.weatherwidget.desktop

import com.weatherwidget.shared.util.UserRefreshCallSites
import com.weatherwidget.test.category.ShortDuration
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
}
