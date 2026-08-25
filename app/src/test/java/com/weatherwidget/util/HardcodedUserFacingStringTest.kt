package com.weatherwidget.util

import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Static guard against hardcoded user-facing strings — the regression class that
 * [LocaleResourceParityTest] cannot see (a literal that never gets a resource key is invisible to
 * XML parity checks). See plans/260825-hardcoded-user-facing-string-guard.md.
 *
 * Two complementary checks, both pure JVM:
 *
 *  - **Check A — `:app` sink scan**: string literals passed directly to unambiguously user-facing
 *    sinks (`drawText`, `setText`, `setTextViewText`, `.text =`, `Toast.makeText`, the
 *    `EXTRA_TOAST_MESSAGE` extra, and notification `setContentTitle/Text/Info`). Precise; catches
 *    the common "add a label inline" regression.
 *  - **Check B — `:shared` prose scan**: prose-like literals in the `:shared` UI surfaces
 *    (`graph`, `actuals`, `notify`, `observations`, `util`, `stats`) that flow into Android UI.
 *    This is the exact shape of the `95c87a92` bug. It is heuristic, so it is scoped narrowly and
 *    every current hit is allowlisted with a reason until Phase 2 localizes it.
 *
 * Both allowlists shrink as Phase 2 moves each string into Android resources: delete the entry the
 * moment the literal is gone from source, and this test then fails if anyone reintroduces it.
 */
@Category(Localization::class)
class HardcodedUserFacingStringTest {

    private companion object {
        // Gradle unit tests run with the module dir as the working dir; the second candidate covers
        // runners started from the repo root (same resolution as LocaleResourceParityTest).
        private val APP_SRC: File =
            sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        private val SHARED_SRC: File =
            sequenceOf(File("../shared/src/main/kotlin"), File("shared/src/main/kotlin"))
                .first { it.isDirectory }

        private val STRING_LITERAL = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")

        /** Sinks where a string literal is unambiguously UI text, with the literal in group 1. */
        private val SINK_PATTERNS =
            listOf(
                Regex("""canvas\.drawText\(\s*"([^"]*)""""),
                Regex("""\.setText\(\s*"([^"]*)""""),
                Regex("""\.setTextViewText\([^,]+,\s*"([^"]*)""""),
                Regex("""\.text\s*=\s*"([^"]*)""""),
                Regex("""Toast\.makeText\([^,]+,\s*"([^"]*)""""),
                Regex("""EXTRA_TOAST_MESSAGE,\s*"([^"]*)""""),
                Regex("""\.setContentTitle\(\s*"([^"]*)""""),
                Regex("""\.setContentText\(\s*"([^"]*)""""),
                Regex("""\.setContentInfo\(\s*"([^"]*)""""),
            )

        /** Lines that carry diagnostics/logs/exceptions rather than UI prose. */
        private val NON_USER_FACING_LINE =
            Regex(
                "Log\\.|\\.log\\(|log\\(|verboseLog|logLabelDecision|eventLogger|appLogDao|logException|" +
                    "throw |require\\(|check\\(|error\\(|Exception|message\\s*=|reason\\s*=|" +
                    "ofPattern|yyyy|HH:mm|EEE|DateTimeFormatter|\\.contains\\(|\\.equals\\(|ignoreCase|->\\s*\"",
            )

        private val TWO_ALPHA_WORDS = Regex("\\b[A-Za-z]+\\s+[A-Za-z]+\\b")
        private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
        /** A string literal immediately followed by `+` — a multi-line log/SQL continuation. */
        private val STRING_CONCAT = Regex(""""\s*\+""")

        // ---- Check A allowlist: current inline sink literals + why they still exist. ----
        private val APP_SINK_ALLOWLIST =
            mapOf(
                "Dead zone tapped" to
                    "debug-only dead-zone toast (TemperatureTouchTargets); gated by BuildConfig.DEBUG",
            )

        // ---- Check B allowlist: current :shared user-facing prose + why it still exists. ----
        private val SHARED_PROSE_ALLOWLIST =
            mapOf(
                "Dominant station temperature changed" to
                    "DominantTempWatchStrings default title (desktop); Android overrides via R.string.notify_dominant_temp_title",
                "last read" to "BlendTableFormatter column header",
                "fed to blend" to "BlendTableFormatter column header",
                "type:  O = official station   P = personal (backyard) station" to
                    "BlendTableFormatter legend",
                "value: R = real reading   E = extrapolated from forecast" to
                    "BlendTableFormatter legend",
                "No blended points in range." to "BlendTableFormatter.renderText empty state",
                " from forecast" to "ForecastDeltaLabel default suffix (desktop); Android passes a localized suffix",
                "Tmrw: Recent History" to "TomorrowIoActuals station name",
                "synoptic: no token configured" to "SynopticObservationFetcher error reason (diagnostic)",
            )
    }

    @Test
    fun `app user-facing sink literals are not hardcoded`() {
        val problems = appSinkLiterals().filterKeys { it !in APP_SINK_ALLOWLIST }
        assertTrue(report("unlocalized :app sink literals", problems), problems.isEmpty())
    }

    @Test
    fun `shared user-facing prose is not hardcoded`() {
        val problems = sharedProseLiterals().filterKeys { it !in SHARED_PROSE_ALLOWLIST }
        assertTrue(report("unlocalized :shared prose", problems), problems.isEmpty())
    }

    /** Literals passed directly to a user-facing sink, keyed literal → `path:line`. */
    private fun appSinkLiterals(): Map<String, String> {
        val found = linkedMapOf<String, String>()
        ktFilesUnder(APP_SRC).forEach { file ->
            val stripped = stripComments(file.readText())
            stripped.lineSequence().forEachIndexed { index, line ->
                for (pattern in SINK_PATTERNS) {
                    val literal = pattern.find(line)?.groupValues?.get(1) ?: continue
                    // Pure text only: a literal with letters and no interpolation. This exempts
                    // placeholders ("--°", "—", "") and string templates built from variables.
                    // Decode \\uXXXX first so the B in "--\\u00B0" is not mistaken for a letter.
                    if (unescapeUnicode(literal).any { it.isLetter() } && '$' !in literal) {
                        found.putIfAbsent(literal, "${file.relativeTo(APP_SRC)}:${index + 1}")
                    }
                }
            }
        }
        return found
    }

    /** Prose-like literals in :shared UI surfaces, keyed literal → `path:line`. */
    private fun sharedProseLiterals(): Map<String, String> {
        val found = linkedMapOf<String, String>()
        ktFilesUnder(SHARED_SRC).forEach { file ->
            val rel = file.relativeTo(SHARED_SRC).path.replace(File.separatorChar, '/')
            // The :shared module's UI-text surface is the com.weatherwidget.shared.* package
            // (graph, actuals, notify, observations, util, stats). data/ (SQL, API clients,
            // models) and widget/ (debug-log-heavy current-temp logic) live beside it and are
            // not UI prose.
            if (!rel.startsWith("com/weatherwidget/shared/")) return@forEach

            val stripped = stripComments(file.readText())
            stripped.lineSequence().forEachIndexed { index, line ->
                if (NON_USER_FACING_LINE.containsMatchIn(line)) return@forEachIndexed
                // $ interpolation is the signature of a debug/log template in :shared; localized
                // prose should use %1$s placeholders instead. A literal glued to `+` is a log/SQL
                // continuation, not a standalone UI string.
                if ('$' in line || STRING_CONCAT.containsMatchIn(line)) return@forEachIndexed
                for (match in STRING_LITERAL.findAll(line)) {
                    val literal = match.groupValues[1]
                    if (isProse(literal)) {
                        found.putIfAbsent(literal, "$rel:${index + 1}")
                    }
                }
            }
        }
        return found
    }

    /** Two or more space-separated words with at least one lowercase letter; not a format/percent. */
    private fun isProse(content: String): Boolean {
        val unescaped =
            content
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\t", " ")
                .replace("\\\\", "\\")
        if (unescaped.none { it.isLowerCase() }) return false
        if (!TWO_ALPHA_WORDS.containsMatchIn(unescaped)) return false
        if (unescaped.contains("\${")) return false
        if (unescaped.trimStart().startsWith("%")) return false
        return true
    }

    private fun unescapeUnicode(s: String): String =
        UNICODE_ESCAPE.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }

    private fun ktFilesUnder(root: File): Sequence<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }

    /** Removes line and (nesting) block comments, preserving string literals verbatim. */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            when {
                source.startsWith("//", i) -> {
                    while (i < n && source[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                source.startsWith("/*", i) -> {
                    var depth = 1
                    out.append("  ")
                    i += 2
                    while (i < n && depth > 0) {
                        when {
                            source.startsWith("/*", i) -> {
                                depth++
                                out.append("  ")
                                i += 2
                            }
                            source.startsWith("*/", i) -> {
                                depth--
                                out.append("  ")
                                i += 2
                            }
                            else -> {
                                out.append(if (source[i] == '\n') '\n' else ' ')
                                i++
                            }
                        }
                    }
                }
                source.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < n && !source.startsWith("\"\"\"", i)) {
                        out.append(source[i])
                        i++
                    }
                    if (i < n) {
                        out.append("\"\"\"")
                        i += 3
                    }
                }
                source[i] == '"' -> {
                    out.append('"')
                    i++
                    while (i < n) {
                        if (source[i] == '\\') {
                            out.append(source[i])
                            i++
                            if (i < n) {
                                out.append(source[i])
                                i++
                            }
                            continue
                        }
                        out.append(source[i])
                        if (source[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun report(label: String, problems: Map<String, String>): String =
        buildString {
            appendLine("$label (${problems.size}):")
            problems.toSortedMap().forEach { (literal, location) ->
                appendLine("  [$location] $literal")
            }
        }
}
