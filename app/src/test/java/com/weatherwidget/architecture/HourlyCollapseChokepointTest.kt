package com.weatherwidget.architecture

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Second chokepoint guard for the coordinate-fragmentation bug family, complementing
 * [HourlyProximityQueryAllowlistTest].
 *
 * That guard asks *"did you collapse the proximity-box rows to a site?"*. It does NOT ask
 * *"is the collapse freshness-correct?"* — and that gap is exactly how the today-column `-13.7`
 * delta shipped (plans/260806-today-column-stale-fragment-delta-opus.md).
 * `HourlyForecastLoader.kt` sat on the allowlist with the justification *"same sameSite filter +
 * stitcher logic"*, which was simply untrue: it collapsed with
 *
 *     .associateBy { Pair(it.dateTime, it.source) }
 *
 * which is last-wins and ignores `fetchedAt`. Because the DAO orders `dateTime ASC` and SQLite
 * breaks ties on `index_hourly_forecasts_locationLat_locationLon` (ascending latitude), a frozen
 * 13-day-old fragment at a higher latitude deterministically overwrote the fresh row.
 *
 * Collapsing hourly rows to one-per-hour is therefore only ever correct through
 * [com.weatherwidget.data.model.HourlyForecastStitcher], which picks `maxByOrNull { fetchedAt }`
 * and same-sites against the raw query centre. This test fails the build when a NEW call site
 * hand-rolls that collapse instead — including call sites that do not exist yet, which no
 * per-call-site test can cover.
 *
 * If this test failed on your new code: use `HourlyForecastStitcher.stitch` (single source) or
 * `stitchBySource` (multi-source). Hand-rolling is a bug even when it looks right today, because
 * whether it works depends on DB row order.
 */
@Category(ShortDuration::class)
class HourlyCollapseChokepointTest {

    private companion object {
        /** How far past the collapse to look for a freshness marker. */
        const val LOOKAHEAD_LINES = 12
    }

    /**
     * A **DB-row** collapse: keyed on `dateTime` together with `source` and/or the storage
     * coordinates. That combination is what distinguishes reducing raw `hourly_forecasts` rows from
     * the many legitimate render-side groupings that bucket already-collapsed points by hour or day
     * (`TemperatureExtrema`, `PrecipProbabilityCalculator`, the view handlers, …). Keying on
     * `dateTime` alone is not flagged — that shape is owned by
     * [com.weatherwidget.data.model.HourlyForecastSelector] and the stitcher, both of which pick
     * `maxByOrNull { fetchedAt }`.
     */
    private val collapsePattern = Regex(
        """\.(associateBy|distinctBy|groupBy)\s*\{[^\n]*\bdateTime\b[^\n]*\b(source|locationLat)\b[^\n]*""",
    )

    /**
     * A collapse is safe when it consults `fetchedAt`, or delegates to one of the sanctioned pickers
     * that does. Checked within the matched line and the lines just after it.
     */
    private val freshnessMarkers = listOf(
        "fetchedAt",
        "pickBestForecast",
        "selectForecastsByTime",
        "HourlyForecastStitcher",
    )


    /**
     * Files allowed to collapse hourly DB rows without consulting freshness, and why.
     * Reviewed 2026-08-06 during the today-column stale-fragment fix.
     */
    private val allowlist = mapOf(
        "GraphDataLoader.kt" to
            "distinctBy keys on dateTime|source|locationLat|locationLon — coordinates ARE in the key, " +
                "so it de-duplicates the overlapping centre/now query windows without collapsing " +
                "fragments; the fragment collapse then happens in HourlyForecastStitcher",
    )

    @Test
    fun hourlyCollapseHappensOnlyInTheSharedStitcher() {
        val offenders = mutableListOf<String>()
        val filesWithCollapse = mutableSetOf<String>()

        sourceRoots().forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    // Strip line comments so the explanatory comments in the loaders (which quote the
                    // old `associateBy` call) do not trip the scan.
                    val lines = file.readLines().map { it.substringBefore("//") }
                    lines.forEachIndexed { idx, line ->
                        if (!collapsePattern.containsMatchIn(line)) return@forEachIndexed
                        filesWithCollapse.add(file.name)
                        val scope = lines.subList(idx, minOf(idx + LOOKAHEAD_LINES, lines.size))
                            .joinToString("\n")
                        val isFreshnessAware = freshnessMarkers.any { it in scope }
                        if (!isFreshnessAware && file.name !in allowlist) {
                            offenders.add("${file.name}:${idx + 1}: ${line.trim()}")
                        }
                    }
                }
        }

        assertTrue(
            "Hourly rows collapsed to one-per-hour outside HourlyForecastStitcher:\n" +
                offenders.joinToString("\n") +
                "\n\nA collapse that ignores fetchedAt lets a frozen coordinate fragment from an " +
                "earlier GPS fix beat the freshest row — whichever the DB happens to return last " +
                "wins, and SQLite breaks dateTime ties by ascending latitude. That produced a " +
                "-13.7 deg today-column delta from a 13-day-old forecast " +
                "(plans/260806-today-column-stale-fragment-delta-opus.md). Use " +
                "HourlyForecastStitcher.stitch / stitchBySource, or add this file to the allowlist " +
                "with a justification.",
            offenders.isEmpty(),
        )

        // Keep the allowlist honest, mirroring HourlyProximityQueryAllowlistTest: an entry whose file
        // no longer collapses is stale and should be removed so the list stays review-worthy.
        val stale = allowlist.keys - filesWithCollapse
        assertTrue("Allowlist entries with no remaining collapse (remove them): $stale", stale.isEmpty())
    }

    /** Unit tests run with the module dir as working dir, but resolve defensively. */
    private fun sourceRoots(): List<File> {
        val candidates = listOf(
            File("src/main/java") to File("../shared/src/main/kotlin"),
            File("app/src/main/java") to File("shared/src/main/kotlin"),
        )
        val pair = candidates.firstOrNull { it.first.isDirectory }
        assertTrue("could not locate source roots from ${File(".").absolutePath}", pair != null)
        return listOfNotNull(pair!!.first, pair.second.takeIf { it.isDirectory })
    }
}
