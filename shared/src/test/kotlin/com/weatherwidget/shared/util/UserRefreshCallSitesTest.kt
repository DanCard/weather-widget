package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** The guard tests' definition of "inside the runner" (plans/261013-observations-refresh-survives-closing-the-screen.md). */
@Category(ShortDuration::class)
class UserRefreshCallSitesTest {
    private val fetch = Regex("""fetchSourceOnDemand\(""")

    @Test
    fun `a call in the trailing lambda is covered, one on lifecycleScope is not`() {
        val src = """
            fun a() {
                userRefreshRunner.start("history") {
                    repo.fetchSourceOnDemand(lat, lon)
                }
            }
            fun b() {
                lifecycleScope.launch { repo.fetchSourceOnDemand(lat, lon) }
            }
        """.trimIndent()
        assertEquals(listOf(7), UserRefreshCallSites.uncovered(src, fetch))
        assertEquals(2, UserRefreshCallSites.count(src, fetch))
    }

    @Test
    fun `afterDetached argument, nested braces and string templates stay inside the block`() {
        val src = """
            refreshRunner.start(
                origin = "user_refresh:${'$'}{origin.trim()}",
                afterDetached = { ok -> if (ok) { repaint() } },
            ) {
                if (x) { log("{") ; repo.fetchSourceOnDemand(a, b) } else { map { it } }
                repo.fetchSourceOnDemand(c, d)
            }
            repo.fetchSourceOnDemand(e, f)
        """.trimIndent()
        assertEquals(listOf(8), UserRefreshCallSites.uncovered(src, fetch))
    }

    @Test
    fun `commented-out calls are ignored, a start without a trailing lambda covers only its arguments`() {
        val src = """
            // repo.fetchSourceOnDemand(old)
            val r = userRefreshRunner.start("x", fetch = { repo.fetchSourceOnDemand(a) })
            repo.fetchSourceOnDemand(b) // after the block
        """.trimIndent()
        assertEquals(listOf(3), UserRefreshCallSites.uncovered(src, fetch))
        assertEquals(2, UserRefreshCallSites.count(src, fetch))
    }
}
