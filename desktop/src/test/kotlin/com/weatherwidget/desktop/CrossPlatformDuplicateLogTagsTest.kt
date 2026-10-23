package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Guard against twins that [CrossPlatformDuplicateNamesTest] cannot see: the same logic under
 * different names (Android `drawWeatherAdaptiveBar` / desktop `drawAdaptiveBar`, 2026-10-23). Copies
 * rename freely but keep their log tag — it is the grep contract — so a tag logged from both
 * `app/src/main` and `desktop/src/main`, and not written in `shared/src/main`, means the code that logs
 * it exists twice (plans/261023-duplicate-logic-audit-different-names.md).
 *
 * Each current one is in [ALLOWED] with why; a new one fails until its logic (and the tag with it)
 * moves to `:shared`, and an entry no longer duplicated fails too, so the list only shrinks.
 */
@Category(ShortDuration::class)
class CrossPlatformDuplicateLogTagsTest {
    private companion object {
        private fun dir(vararg candidates: String) = candidates.map(::File).first { it.isDirectory }

        // Unit tests run with the module dir as working dir; the second candidate is the repo root.
        val APP = dir("../app/src/main", "app/src/main")
        val DESKTOP = dir("src/main", "desktop/src/main")
        val SHARED = dir("../shared/src/main", "shared/src/main")

        /** An UPPER_SNAKE string literal with at least one underscore, as log tags are written. */
        val TAG = Regex(""""([A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+)\b""")

        private const val ADAPTER = "platform adapter: the rule is in :shared, each platform logs its own event source"
        private const val FETCH_JOB = "post-fetch job, shared planner + platform glue — pending a Runner + Host (audit 261023 §2)"
        private const val UI_FLOW = "user-action flow written twice — pending a shared coordinator (audit 261023 §3)"

        val ALLOWED = mapOf(
            "GOOGLE_REQUEST" to ADAPTER, // OkHttp interceptor vs desktop HTTP client
            "SOURCE_FETCH_GATE" to ADAPTER, // SourceFetchGate loaders
            "SOURCE_VIEW_PROBABILITY" to ADAPTER, // SourceViewProbability, daily log per platform store
            "TIMEZONE_CHANGE" to ADAPTER, // broadcast vs daemon clock check
            "DOMINANT_TEMP_WATCH" to ADAPTER, // DominantTempWatch, prefs vs file store
            "CLIMATE_FETCH_EMPTY" to FETCH_JOB,
            "DAILY_HISTORY_KEPT_UNCOVERED" to FETCH_JOB,
            "TODAY_LOW_UNCOVERED" to FETCH_JOB,
            "FETCH_PLAN" to FETCH_JOB,
            "GOOGLE_HOURS_PAGES" to FETCH_JOB,
            "HOURLY_ON_DEMAND" to FETCH_JOB,
            "HOURLY_TRIM" to FETCH_JOB,
            "NOWCAST_FETCH" to FETCH_JOB,
            "NOWCAST_SKIP" to FETCH_JOB,
            "NOWCAST_SUMMARY" to FETCH_JOB,
            "NWS_STATION_ACTUALS" to FETCH_JOB,
            "NWS_STATION_ACTUALS_FAIL" to FETCH_JOB,
            "NWS_STATION_ACTUALS_OUTCOME" to FETCH_JOB,
            "NWS_STATION_FAIL" to FETCH_JOB, // current observation
            "OBS_ATTEMPT_TOUCH" to FETCH_JOB,
            "OBS_CLOUD_CARRIER" to FETCH_JOB,
            "PRIOR_CLOUD" to FETCH_JOB,
            "PRIOR_CLOUD_EMPTY" to FETCH_JOB,
            "TMRW_5M_FETCH" to FETCH_JOB,
            "WAPI_HISTORY_CHECK" to FETCH_JOB,
            "WAPI_HISTORY_RESULT" to FETCH_JOB,
            "CLICK_DAILY" to UI_FLOW,
            "HEADER_FIT" to UI_FLOW, // measuring glue around shared HeaderFit
            "HISTORY_REFRESH_DONE" to UI_FLOW,
            "LOCATION_FETCH_PENDING" to UI_FLOW,
            "REFRESH_TOAST" to UI_FLOW,
            "SIM_LOOP_ERROR" to UI_FLOW, // rain-loop player, audit 261021 H
            "SIM_LOOP_FALLBACK" to UI_FLOW,
            "SOURCE_SWITCH_FETCH" to UI_FLOW,
        )

        fun tags(root: File): Set<String> =
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> TAG.findAll(file.readText()).map { it.groupValues[1] } }
                .toSet()
    }

    @Test
    fun `no log tag is written twice outside shared`() {
        val twins = (tags(APP) intersect tags(DESKTOP)) - tags(SHARED)
        val unexpected = twins - ALLOWED.keys
        val stale = ALLOWED.keys - twins
        assertTrue(
            "Logged from both app and desktop but not :shared — the code around it is a twin; move it " +
                "(and the tag) to :shared, or allowlist it with a reason: $unexpected",
            unexpected.isEmpty(),
        )
        assertTrue("No longer duplicated — remove from ALLOWED: $stale", stale.isEmpty())
    }
}
