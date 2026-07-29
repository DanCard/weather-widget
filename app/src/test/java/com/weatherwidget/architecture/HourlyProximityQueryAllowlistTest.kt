package com.weatherwidget.architecture

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Chokepoint guard for the coordinate-fragmentation bug family
 * (plans/260710-daily-cloud-cover-flap-stale-fragment.md and its predecessors: hourly
 * quantize+Selector, forecasts Selector, in-memory pin sameSite).
 *
 * The raw hourly proximity-box DAO queries (`getHourlyForecasts` / `getHourlyForecastsBySource`)
 * intentionally over-fetch: they return rows from EVERY cached coordinate site inside the
 * LocationMatch box, including frozen fragments left by earlier GPS fixes. Consumers that feed
 * rendering or firstOrNull-style selection MUST collapse to one site first
 * (`GraphDataLoader.unifyToNearestSite`, or the graph path's sameSite filter + stitcher).
 * Every recurrence of this bug has been a NEW call site skipping that step — a per-call-site
 * unit test cannot catch a path that doesn't exist yet, so this test pins the caller surface.
 *
 * If this test failed on your new code: either route the read through GraphDataLoader, or —
 * if the caller genuinely needs raw per-site rows (e.g. write-path dedup) — add the file to
 * the allowlist WITH a justifying comment at the call site.
 */
@Category(ShortDuration::class)
class HourlyProximityQueryAllowlistTest {

    private val rawCallPattern = Regex("""\.getHourlyForecasts(BySource)?\(""")

    /**
     * Files allowed to call the raw queries directly, with the reason they are exempt.
     * Reviewed 2026-07-10 during the daily cloud-cover flap fix.
     */
    private val allowlist = mapOf(
        "HourlyForecastDao.kt" to "the DAO itself",
        "GraphDataLoader.kt" to "the sanctioned chokepoint (sameSite filter / stitcher / unifyToNearestSite)",
        "DailyInteractionRenderer.kt" to "daily render query is immediately wrapped in unifyToNearestSite",
        "DailyActualsLoader.kt" to "extracted from WidgetIntentRouter on 2026-07-28; sole call site wrapped in unifyToNearestSite",
        "SourceStalenessProbe.kt" to "extracted from WidgetIntentRouter on 2026-07-28; sole call site wrapped in unifyToNearestSite",
        "WidgetStartupCoordinator.kt" to "startup raw rows are unified downstream by WidgetRenderer",
        "HourlyForecastStore.kt" to "write-path dedup filters to the exact quantized site",
        "DailyHistorySnapshotter.kt" to "freeze calculations use site-aware rain and noon-cloud resolvers",
        "CurrentTempRepository.kt" to "pre-existing; audit before touching (current-temp windows)",
        "ObservationRepository.kt" to "pre-existing; audit before touching (actuals context)",
        "UIUpdateScheduler.kt" to "pre-existing; audit before touching (update cadence heuristics)",
        "DataFreshness.kt" to "staleness check; freshest row wins regardless of site",
        "WeatherWidgetWorker.kt" to "pre-existing; audit before touching",
        "NoHourlyDayClickCoordinator.kt" to "pre-existing presence gate; audit before touching",
    )

    @Test
    fun rawHourlyProximityQueriesOnlyFromAllowlistedFiles() {
        val srcRoot = findMainSourceRoot()
        val offenders = mutableListOf<String>()
        val filesWithCalls = mutableSetOf<String>()

        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val code = line.substringBefore("//")
                    if (rawCallPattern.containsMatchIn(code)) {
                        filesWithCalls.add(file.name)
                        if (file.name !in allowlist) {
                            offenders.add("${file.name}:${idx + 1}: ${line.trim()}")
                        }
                    }
                }
            }

        if (offenders.isNotEmpty()) {
            fail(
                "New raw getHourlyForecasts* call site(s) outside the allowlist:\n" +
                    offenders.joinToString("\n") +
                    "\n\nThe proximity-box query returns rows from EVERY cached coordinate site, " +
                    "including stale fragments from earlier GPS fixes — selections over the raw " +
                    "list resurrect the daily cloud-cover flap " +
                    "(plans/260710-daily-cloud-cover-flap-stale-fragment.md). Route the read " +
                    "through GraphDataLoader.unifyToNearestSite / loadGraphWindowHourlyForecasts, " +
                    "or add the file to this allowlist with a justification.",
            )
        }

        // Keep the allowlist honest: an entry whose file no longer calls the raw queries is
        // stale and should be removed so the list stays a review-worthy surface.
        val stale = allowlist.keys - filesWithCalls - setOf("HourlyForecastDao.kt")
        assertTrue(
            "Allowlist entries with no remaining raw calls (remove them): $stale",
            stale.isEmpty(),
        )
    }

    /** Unit tests run with the module dir as working dir, but resolve defensively. */
    private fun findMainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("could not locate app main source root from ${File(".").absolutePath}", root != null)
        return root!!
    }
}
