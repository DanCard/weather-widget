package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Guard against Android and desktop growing their own copies of the same logic — the bug class the
 * user keeps hitting (plans/261021-android-desktop-shared-code-audit.md §5).
 *
 * A function name (6+ chars, not an `override`) declared in both `app/src/main` and
 * `desktop/src/main` but nowhere in `shared/src/main` is a likely twin. Each current one is in
 * [ALLOWED] with why; a new one fails here until it moves to `:shared` (or is allowlisted with a
 * reason), and an entry that is no longer duplicated fails too, so the list only shrinks.
 */
@Category(ShortDuration::class)
class CrossPlatformDuplicateNamesTest {
    private companion object {
        private fun dir(vararg candidates: String) = candidates.map(::File).first { it.isDirectory }

        // Unit tests run with the module dir as working dir; the second candidate is the repo root.
        val APP = dir("../app/src/main", "app/src/main")
        val DESKTOP = dir("src/main", "desktop/src/main")
        val SHARED = dir("../shared/src/main", "shared/src/main")

        val DECLARATION = Regex("""^(?!.*\boverride\b).*\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z_][A-Za-z0-9_.<>?, ]*\.)?([a-z][A-Za-z0-9_]{5,})\s*\(""")

        private const val ADAPTER = "platform adapter: the rule is in :shared, this only reads/writes the platform store"
        private const val DRAWING = "draw primitive over Canvas vs Compose DrawScope; geometry already shared"
        private const val GENERIC = "generic name of unrelated members"
        private const val PENDING = "real twin — pending a move to :shared"

        val ALLOWED: Map<String, String> = mapOf(
            "refillStaleGoogleHours" to ADAPTER, // GoogleHistoryRun
            "markHorizon" to ADAPTER, // ForecastHorizonMarkers.mark
            "recordProductQuotas" to ADAPTER, // QuotaState.record
            "pruneHistorySnapshotsIfDue" to ADAPTER, // HistoryPruneSchedule
            "toMaintenanceRow" to ADAPTER, // row mapping into DailyHistoryMaintenance
            "toHourlyForecast" to ADAPTER, // entity/API row mapping into the shared model
            "cachedNormalsByMonthDay" to ADAPTER, // climate_normals read
            "fetchCurrentConditions" to ADAPTER, // CurrentConditionsRun.Host fetch
            "fetchPriorDayCloudForecast" to ADAPTER, // PriorDayCloudRun.Host fetch / store
            "getPowerState" to ADAPTER, // BatteryManager vs /sys/class/power_supply
            "isArmed" to ADAPTER, // DominantTempWatch prefs vs file store
            "setArmed" to ADAPTER,
            "resetLogMemoForTesting" to ADAPTER, // SourceFetchGate loaders
            "getIconResource" to ADAPTER, // icon name is shared (DailyIconName); drawable lookup is not
            "showOutcome" to ADAPTER, // RefreshNotice.Gate.restart + outcomeLine; Toast vs Compose pill
            "calculateRainAmountPlacements" to ADAPTER, // RainAmountLabelPlacer (+ PADDING_DP); rect types, Paint vs TextMeasurer
            "drawActualLine" to DRAWING,
            "drawDayLabels" to DRAWING,
            "drawGridAndAxes" to DRAWING,
            "drawNowLine" to DRAWING,
            "drawOutlinedText" to DRAWING,
            "drawSeriesCurve" to DRAWING,
            "cancel" to GENERIC,
            "columns" to GENERIC,
            "expire" to GENERIC,
            "labelFor" to GENERIC,
            "remove" to GENERIC,
            "update" to GENERIC,
        )

        fun declaredNames(root: File): Set<String> =
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> file.readLines().mapNotNull { DECLARATION.find(it)?.groupValues?.get(1) } }
                .toSet()
    }

    @Test
    fun `no function is written twice outside shared`() {
        val twins = (declaredNames(APP) intersect declaredNames(DESKTOP)) - declaredNames(SHARED)
        val unexpected = twins - ALLOWED.keys
        val stale = ALLOWED.keys - twins
        assertTrue(
            "Declared in both app and desktop but not :shared — move the logic to :shared, or allowlist " +
                "it with a reason: $unexpected",
            unexpected.isEmpty(),
        )
        assertTrue("No longer duplicated — remove from ALLOWED: $stale", stale.isEmpty())
    }
}
