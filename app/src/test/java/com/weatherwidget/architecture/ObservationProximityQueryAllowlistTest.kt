package com.weatherwidget.architecture

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * The observation-side sibling of [HourlyProximityQueryAllowlistTest], guarding the same
 * coordinate-fragmentation bug family on the `observations` table.
 *
 * `getObservationCandidatesInRange` is the raw proximity-box read: it returns rows from EVERY
 * cached device-site inside the LocationMatch box, including fragments left by a GPS excursion.
 * Consumers must go through `ObservationDao.getObservationsInRange`, which applies
 * `ObservationSiteMerge` — merging fragments that describe the same sky and deduplicating on
 * `(station, timestamp, api)`.
 *
 * Added 2026-08-27 after an ~800 m walk cost both the cloud and temperature actual lines a
 * 75-minute hole: every observation in that window was filed under a second fragment, and the
 * then-current single-site collapse deleted all of them. Every recurrence in this family has been a
 * new call site skipping site handling, which a per-call-site test cannot catch for a path that does
 * not exist yet — so this pins the caller surface instead.
 *
 * If this failed on your new code: read through `getObservationsInRange`, or — if you genuinely need
 * raw per-site rows — add the file here WITH a justification at the call site.
 */
@Category(ShortDuration::class)
class ObservationProximityQueryAllowlistTest {

    private val rawCallPattern = Regex("""\.getObservationCandidatesInRange\(|selectNearestObservationSite\(""")

    private val allowlist = mapOf(
        "ObservationDao.kt" to "the DAO itself; wraps the raw read in ObservationSiteMerge",
        "ObservationEntity.kt" to "declares selectNearestObservationSite",
        // Deliberately NOT migrated to the merge. This picks the single row that is 'now' rather
        // than assembling a series, so 'which device site is current' is the question being asked,
        // not an obstacle to it. Whether an excursion should also widen the current-temp read is a
        // separate question and was left open by
        // plans/260827-observation-site-merge-for-actual-series.md.
        "CurrentObservationReader.kt" to "current-observation pick; site identity is the question, not a filter",
    )

    @Test
    fun rawObservationProximityQueriesOnlyFromAllowlistedFiles() {
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
                "New raw observation proximity-box call site(s) outside the allowlist:\n" +
                    offenders.joinToString("\n") +
                    "\n\nThe box read returns rows from EVERY cached device-site, and a single-site " +
                    "collapse over it deletes the fragments a GPS excursion created — which is how " +
                    "an 800 m walk blanked 75 minutes of both actual lines " +
                    "(plans/260827-observation-site-merge-for-actual-series.md). Read through " +
                    "ObservationDao.getObservationsInRange, or add the file here with a justification.",
            )
        }

        val stale = allowlist.keys - filesWithCalls
        assertTrue(
            "Allowlist entries with no remaining raw calls (remove them): $stale",
            stale.isEmpty(),
        )
    }

    private fun findMainSourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("could not locate app main source root from ${File(".").absolutePath}", root != null)
        return root!!
    }
}
