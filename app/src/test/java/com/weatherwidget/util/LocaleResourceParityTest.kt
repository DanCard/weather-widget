package com.weatherwidget.util

import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static parity checks across every locale's strings.xml (Tier 1 of
 * notes/260709-localization-testplan.md). aapt validates XML syntax but none of these:
 * a missing key is a silent English fallback in one language, and a dropped or
 * type-changed positional arg (%2$s) is a runtime crash in exactly one language.
 *
 * Pure JVM — parses the res XML directly, no Robolectric.
 */
@Category(Localization::class)
class LocaleResourceParityTest {
    private data class StringRes(
        val name: String,
        val rawText: String,
        val translatable: Boolean,
        val formatted: String?,
    )

    private companion object {
        // Gradle unit tests run with the module dir as the working dir; the second
        // candidate covers runners started from the repo root.
        val resDir: File =
            sequenceOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.isDirectory }

        val POSITIONAL_ARG = Regex("""%\d+\$(?:\.\d+)?[sdf]""")
        val BARE_ARG = Regex("""%[sdf]""")

        fun parseStrings(file: File): Map<String, StringRes> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.getElementsByTagName("string")
            return (0 until nodes.length)
                .asSequence()
                .map { nodes.item(it) as Element }
                .associate { el ->
                    el.getAttribute("name") to
                        StringRes(
                            name = el.getAttribute("name"),
                            rawText = el.textContent,
                            translatable = el.getAttribute("translatable") != "false",
                            formatted = el.getAttribute("formatted").ifEmpty { null },
                        )
                }
        }

        /**
         * aapt-lite normalization: trims unquoted edge whitespace and unwraps a
         * fully-quoted value — enough to compare edge whitespace the way aapt ships it.
         */
        fun effective(raw: String): String {
            val t = raw.trim()
            return if (t.length >= 2 && t.first() == '"' && t.last() == '"') t.substring(1, t.length - 1) else t
        }

        /** CJK fullwidth punctuation legitimately absorbs an adjacent ASCII space. */
        fun isFullwidthPunctuation(c: Char): Boolean = c in '　'..'〿' || c in '！'..'｠'
    }

    private val base: Map<String, StringRes> by lazy { parseStrings(File(resDir, "values/strings.xml")) }
    private val baseTranslatable: Map<String, StringRes> by lazy { base.filterValues { it.translatable } }

    /** Folder suffix ("de", "zh-rCN") → parsed strings, for every shipped translation. */
    private val locales: Map<String, Map<String, StringRes>> by lazy {
        resDir
            .listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
            .filter { File(it, "strings.xml").isFile }
            .associate { it.name.removePrefix("values-") to parseStrings(File(it, "strings.xml")) }
    }

    private fun assertNoProblems(problems: List<String>) {
        assertTrue("\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every locale has exactly the base translatable keys`() {
        val expected = baseTranslatable.keys
        val problems =
            buildList {
                locales.forEach { (locale, strings) ->
                    (expected - strings.keys).sorted().takeIf { it.isNotEmpty() }?.let { add("[$locale] missing: $it") }
                    (strings.keys - expected).sorted().takeIf { it.isNotEmpty() }?.let { add("[$locale] extra: $it") }
                }
            }
        assertNoProblems(problems)
    }

    @Test
    fun `positional format args match base in every locale`() {
        val problems =
            buildList {
                locales.forEach { (locale, strings) ->
                    strings.forEach { (name, translated) ->
                        val baseRes = baseTranslatable[name] ?: return@forEach
                        val expected = POSITIONAL_ARG.findAll(baseRes.rawText).map { it.value }.sorted().toList()
                        val actual = POSITIONAL_ARG.findAll(translated.rawText).map { it.value }.sorted().toList()
                        if (expected != actual) {
                            add("[$locale] $name: base args $expected but translation has $actual")
                        }
                    }
                }
            }
        assertNoProblems(problems)
    }

    @Test
    fun `no bare format specifiers introduced`() {
        // A translator turning %1$s into %s compiles fine and crashes at runtime when the
        // string is formatted with more than one argument. Only formatted strings matter.
        val problems =
            buildList {
                locales.forEach { (locale, strings) ->
                    strings.forEach { (name, translated) ->
                        val baseRes = baseTranslatable[name] ?: return@forEach
                        if (baseRes.formatted == "false") return@forEach
                        val expected = BARE_ARG.findAll(baseRes.rawText).count()
                        val actual = BARE_ARG.findAll(translated.rawText).count()
                        if (expected != actual) {
                            add("[$locale] $name: bare specifier count $actual (base has $expected)")
                        }
                    }
                }
            }
        assertNoProblems(problems)
    }

    @Test
    fun `literal percent counts match base`() {
        // %% position may move for grammar (ja/zh/tr moved it), but the count must match
        // or formatting throws UnknownFormatConversionException in that locale only.
        val problems =
            buildList {
                locales.forEach { (locale, strings) ->
                    strings.forEach { (name, translated) ->
                        val baseRes = baseTranslatable[name] ?: return@forEach
                        val expected = Regex("%%").findAll(baseRes.rawText).count()
                        val actual = Regex("%%").findAll(translated.rawText).count()
                        if (expected != actual) {
                            add("[$locale] $name: %% count $actual (base has $expected)")
                        }
                    }
                }
            }
        assertNoProblems(problems)
    }

    @Test
    fun `formatted attribute mirrors base`() {
        // formatted="false" protects literal percent text (personal_stations_*); a locale
        // dropping it turns "0% · sin descuento" into a format-parse crash.
        val problems =
            buildList {
                locales.forEach { (locale, strings) ->
                    strings.forEach { (name, translated) ->
                        val baseRes = baseTranslatable[name] ?: return@forEach
                        if (baseRes.formatted != translated.formatted) {
                            add(
                                "[$locale] $name: formatted=${translated.formatted} " +
                                    "(base has formatted=${baseRes.formatted})",
                            )
                        }
                    }
                }
            }
        assertNoProblems(problems)
    }

    @Test
    fun `no locale defines a non-translatable base key`() {
        // Keeps lint's MissingTranslation signal meaningful: translatable="false" strings
        // (pure formats, log levels) must exist only in the base file.
        val nonTranslatable = base.filterValues { !it.translatable }.keys
        val problems =
            buildList {
                locales.forEach { (locale, strings) ->
                    (strings.keys intersect nonTranslatable).sorted().takeIf { it.isNotEmpty() }?.let {
                        add("[$locale] defines non-translatable keys: $it")
                    }
                }
            }
        assertNoProblems(problems)
    }

    @Test
    fun `locales_config matches the shipped locale folders`() {
        // The Android 13+ picker offers exactly the locales_config entries; an entry with
        // no folder silently falls back to English, a folder with no entry is unreachable
        // from the picker. The two non-identity mappings are the sharp edge:
        // zh-CN ↔ values-zh-rCN and (BCP-47 "id" aside) in ↔ values-in.
        val configFile = File(resDir, "xml/locales_config.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile)
        val nodes = doc.documentElement.getElementsByTagName("locale")
        val configTags =
            (0 until nodes.length)
                .map { (nodes.item(it) as Element).getAttribute("android:name") }
                .toSet()

        fun folderSuffixFor(tag: String): String? {
            if (tag == "en") return null // base values/
            val parts = tag.split("-")
            return if (parts.size == 2) "${parts[0]}-r${parts[1]}" else parts[0]
        }

        val problems =
            buildList {
                if ("en" !in configTags) add("locales_config is missing the base locale 'en'")
                configTags.filter { it != "en" }.forEach { tag ->
                    val suffix = folderSuffixFor(tag)
                    if (suffix !in locales.keys) {
                        add("locales_config declares '$tag' but res/values-$suffix/strings.xml does not exist")
                    }
                }
                val reachable = configTags.mapNotNull { folderSuffixFor(it) }.toSet()
                (locales.keys - reachable).sorted().forEach { suffix ->
                    add("res/values-$suffix exists but locales_config has no entry for it")
                }
            }
        assertNoProblems(problems)
    }

    @Test
    fun `edge whitespace preserved, fullwidth punctuation exempt`() {
        // Strings like obs_fetched_separator (" • Fetched ") and the bias suffixes are
        // concatenated in code; losing the edge space runs words together. CJK locales
        // legitimately replace "<space>(" / ": <space>" with fullwidth ／（：） forms.
        val problems =
            buildList {
                baseTranslatable.forEach { (name, baseRes) ->
                    val baseEff = effective(baseRes.rawText)
                    val lead = baseEff.takeWhile { it.isWhitespace() }
                    val trail = baseEff.takeLastWhile { it.isWhitespace() }
                    if (lead.isEmpty() && trail.isEmpty()) return@forEach
                    locales.forEach { (locale, strings) ->
                        val translated = strings[name] ?: return@forEach
                        val eff = effective(translated.rawText)
                        val leadOk =
                            lead.isEmpty() || eff.startsWith(lead) ||
                                (eff.firstOrNull()?.let { isFullwidthPunctuation(it) } == true)
                        val trailOk =
                            trail.isEmpty() || eff.endsWith(trail) ||
                                (eff.lastOrNull()?.let { isFullwidthPunctuation(it) } == true)
                        if (!leadOk) add("[$locale] $name: lost leading whitespace (base has ${lead.length})")
                        if (!trailOk) add("[$locale] $name: lost trailing whitespace (base has ${trail.length})")
                    }
                }
            }
        assertNoProblems(problems)
    }
}
