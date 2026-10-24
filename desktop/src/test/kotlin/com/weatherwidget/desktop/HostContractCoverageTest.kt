package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Every shared runner's port (`interface Host` / `interface Store` nested in a `:shared` class) has
 * a contract suite in `:shared` testFixtures that both platforms run against their real database —
 * an abstract class naming `<Runner>.Host`, extended in `app/src/test` and in `desktop/src/test`
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md). The runner's tests use fakes, so
 * without one a Host whose SQL means something else on one platform passes everything.
 *
 * [PENDING] lists the ports not covered yet; it only shrinks — an entry that gains a suite fails here.
 */
@Category(ShortDuration::class)
class HostContractCoverageTest {
    private companion object {
        private fun dir(vararg candidates: String) = candidates.map(::File).first { it.isDirectory }

        // Unit tests run with the module dir as working dir; the second candidate is the repo root.
        val SHARED = dir("../shared/src/main", "shared/src/main")
        val FIXTURES = dir("../shared/src/testFixtures", "shared/src/testFixtures")
        val APP_TESTS = dir("../app/src/test", "app/src/test")
        val DESKTOP_TESTS = dir("src/test", "desktop/src/test")

        val PORT = Regex("""^\s+(?:fun\s+)?interface\s+(Host|Store)\b""", RegexOption.MULTILINE)
        val CONTRACT_CLASS = Regex("""abstract\s+class\s+(\w+)""")

        val PENDING = setOf(
            "ClimateNormalsRun.Host",
            "PriorDayCloudRun.Host",
            "NwsStationActualsRun.Host",
            "HourlyOnDemandRun.Host",
            "WeatherApiHistoryRun.Host",
            "ElapsedForecastBackfill.Store",
            "GoogleHistoryRun.Host",
            "CurrentConditionsRun.Host",
            "NowcastRefreshRun.Host",
            "NwsStationBundles.Host",
        )

        fun kotlinFiles(root: File) = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
    }

    private fun ports(): Set<String> = kotlinFiles(SHARED).flatMap { file ->
        PORT.findAll(file.readText()).map { "${file.nameWithoutExtension}.${it.groupValues[1]}" }
    }.toSet()

    /** Port → contract class names in testFixtures that mention it. */
    private fun contracts(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        for (file in kotlinFiles(FIXTURES)) {
            val text = file.readText()
            val contract = CONTRACT_CLASS.find(text)?.groupValues?.get(1) ?: continue
            ports().filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }
                .forEach { result.getOrPut(it) { mutableListOf() }.add(contract) }
        }
        return result
    }

    private fun extendedIn(root: File, contract: String) =
        kotlinFiles(root).any { Regex(""":\s*$contract\s*\(\s*\)""").containsMatchIn(it.readText()) }

    @Test
    fun `every runner port has a contract suite run on both platforms`() {
        val contracts = contracts()
        val missing = ports().filter { it !in PENDING }.mapNotNull { port ->
            val names = contracts[port].orEmpty()
            when {
                names.isEmpty() -> "$port: no abstract contract in shared/src/testFixtures"
                names.none { extendedIn(APP_TESTS, it) } -> "$port: ${names.joinToString()} not extended in app/src/test"
                names.none { extendedIn(DESKTOP_TESTS, it) } -> "$port: ${names.joinToString()} not extended in desktop/src/test"
                else -> null
            }
        }
        assertTrue("Ports without a two-platform contract suite:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `pending list only shrinks`() {
        val ports = ports()
        val contracts = contracts()
        val stale = PENDING.filter { it !in ports || it in contracts }
        assertTrue("Remove from PENDING (gone, or now has a contract): $stale", stale.isEmpty())
    }
}
