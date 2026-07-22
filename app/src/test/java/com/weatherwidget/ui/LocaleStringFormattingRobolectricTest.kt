package com.weatherwidget.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.Localization
import com.weatherwidget.widget.WidgetRenderer
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tier 2 of notes/260709-localization-testplan.md: Tier 1 (LocaleResourceParityTest) proves
 * the XML agrees with itself; this proves Android can actually FORMAT and RESOLVE every
 * string, layout, and locale-derived default. No text is measured (Robolectric has no font
 * engine) — assertions are about resolution, formatting, and which value got bound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(Localization::class)
class LocaleStringFormattingRobolectricTest {
    private companion object {
        val POSITIONAL_ARG = Regex("""%(\d+)\$(?:\.\d+)?([sdf])""")

        /**
         * Every shipped locale qualifier, discovered from res/values-* so this test can never
         * drift from LocaleResourceParityTest's locales_config bijection check — a locale
         * added to one is automatically covered by the other. "en" (base) is added explicitly
         * since it has no values-en folder.
         */
        fun shippedQualifiers(): List<String> {
            val resDir =
                sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
            val folders =
                resDir
                    .listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
                    .map { it.name.removePrefix("values-") }
                    .sorted()
            return listOf("en") + folders
        }

        /**
         * Names with `formatted="false"` in the base resource (currently the 3
         * personal_stations_* strings with literal "%"). Production code calls the no-arg
         * `getString(id)` for these — the ONLY safe call, since `String.format` throws
         * `UnknownFormatConversionException` on an unescaped bare "%". Every other string
         * goes through the varargs overload below, even with zero detected args: an empty
         * vararg array still routes through java.util.Formatter and parses the WHOLE
         * string, so a malformed conversion char (e.g. "%1$q") throws even when no valid
         * "%N$[sdf]" spec was found to build a dummy arg from.
         */
        fun formattedFalseNames(): Set<String> {
            val resDir =
                sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
            val doc =
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "values/strings.xml"))
            val nodes = doc.documentElement.getElementsByTagName("string")
            return (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .filter { it.getAttribute("formatted") == "false" }
                .map { it.getAttribute("name") }
                .toSet()
        }

        fun dummyArgsFor(raw: String): Array<Any> {
            val specs = POSITIONAL_ARG.findAll(raw).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
            if (specs.isEmpty()) return emptyArray()
            val typeByIndex = specs.associate { it.first to it.second }
            val maxIndex = specs.maxOf { it.first }
            return (1..maxIndex)
                .map { i ->
                    when (typeByIndex[i]) {
                        "d" -> 1
                        "f" -> 1.0f
                        else -> "x"
                    } as Any
                }.toTypedArray()
        }
    }

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `every string formats without throwing in every shipped locale`() {
        val stringFields = R.string::class.java.fields
        val qualifiers = shippedQualifiers()
        val noFormat = formattedFalseNames()
        val failures = mutableListOf<String>()

        qualifiers.forEach { qualifier ->
            RuntimeEnvironment.setQualifiers(qualifier)
            val context = app()
            stringFields.forEach { field ->
                val id = field.getInt(null)
                val name = field.name
                try {
                    if (name in noFormat) {
                        context.getString(id)
                    } else {
                        val raw = context.resources.getText(id).toString()
                        // Always the varargs overload, even with 0 args: an empty array still
                        // routes through java.util.Formatter, so a malformed conversion char
                        // throws even when dummyArgsFor recognized no valid spec to build from.
                        context.getString(id, *dummyArgsFor(raw))
                    }
                } catch (e: Exception) {
                    failures += "[$qualifier] $name: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }

        assertTrue(
            "${failures.size} string(s) failed to format:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `RTL locales resolve RTL layout direction`() {
        RuntimeEnvironment.setQualifiers("ar")
        assertEquals(
            "Arabic must resolve RTL",
            View.LAYOUT_DIRECTION_RTL,
            app().resources.configuration.layoutDirection,
        )

        RuntimeEnvironment.setQualifiers("ur")
        assertEquals(
            "Urdu must resolve RTL",
            View.LAYOUT_DIRECTION_RTL,
            app().resources.configuration.layoutDirection,
        )

        // Control: a shipped LTR locale must NOT report RTL (proves the assertion above is
        // actually discriminating, not vacuously true for every qualifier).
        RuntimeEnvironment.setQualifiers("de")
        assertEquals(
            "German must resolve LTR",
            View.LAYOUT_DIRECTION_LTR,
            app().resources.configuration.layoutDirection,
        )
    }

    @Test
    fun `settings and config layouts inflate under RTL without crashing`() {
        listOf("ar", "ur").forEach { qualifier ->
            RuntimeEnvironment.setQualifiers(qualifier)
            val context = app()
            val themed = ContextThemeWrapper(context, R.style.Theme_WeatherWidget)
            val inflater = LayoutInflater.from(themed)
            val root = FrameLayout(themed)

            // The assertion IS that these don't throw — a resource that only resolves under
            // LTR (or a layout attribute that mis-resolves under RTL) throws InflateException
            // here rather than surfacing as a silent visual bug on a real device.
            inflater.inflate(R.layout.activity_settings, root, false)
            inflater.inflate(R.layout.activity_config, root, false)
        }
    }

    private fun mockWidgetManager(appWidgetId: Int): Pair<AppWidgetManager, CapturingSlot<RemoteViews>> {
        val appWidgetManager = mockk<AppWidgetManager>()
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit
        return appWidgetManager to viewsSlot
    }

    @Test
    fun `widget loading placeholder rebinds text on every locale switch via reapply`() = kotlinx.coroutines.runBlocking {
        val appWidgetId = 4242
        // de (longest strings), ar (RTL), zh-rCN (CJK), bn (complex script) — per plan Tier 2 #3.
        val qualifiers = listOf("en", "de", "ar", "zh-rCN", "bn")

        RuntimeEnvironment.setQualifiers(qualifiers.first())
        var context = app()
        val (firstManager, firstSlot) = mockWidgetManager(appWidgetId)
        WidgetRenderer.updateWidgetLoading(context, firstManager, appWidgetId)
        val root = FrameLayout(context)
        val applied = firstSlot.captured.apply(context, root)

        qualifiers.drop(1).forEach { qualifier ->
            RuntimeEnvironment.setQualifiers(qualifier)
            context = app()
            // Resolved fresh under the NEW qualifier — the assertion below proves the widget
            // binder reads resources at bind time rather than reusing a cached earlier value.
            val expectedToday = context.getString(R.string.today)
            val expectedLoading = context.getString(R.string.widget_loading)

            val (manager, slot) = mockWidgetManager(appWidgetId)
            WidgetRenderer.updateWidgetLoading(context, manager, appWidgetId)
            // reapply(), not apply(): proves rebinding onto the PREVIOUS locale's already-
            // inflated tree, not a fresh one — the RemoteViews-visibility-is-sticky failure
            // mode applies to text too if a binder ever skips setTextViewText conditionally.
            slot.captured.reapply(context, applied)

            assertEquals(
                "[$qualifier] day2_label",
                expectedToday,
                applied.findViewById<TextView>(R.id.day2_label).text.toString(),
            )
            assertEquals(
                "[$qualifier] day2_low",
                expectedLoading,
                applied.findViewById<TextView>(R.id.day2_low).text.toString(),
            )
        }
    }

    @Test
    fun `default test qualifiers resolve to Fahrenheit (documented assumption for other suites)`() {
        // en-rUS is Robolectric's default when no setQualifiers call is made; every other
        // Robolectric suite in this repo implicitly assumes useCelsius()==false under it.
        // Set explicitly so this test documents and guards that assumption directly, rather
        // than relying on ambient default state another test could quietly change.
        RuntimeEnvironment.setQualifiers("en-rUS")
        val stateManager = WidgetStateManager(app())
        assertFalse(
            "en-rUS must default to Fahrenheit; 40+ Robolectric suites assume this",
            stateManager.useCelsius(),
        )
    }

    @Test
    fun `per-app language does not override device region for units`() {
        // Locks in the deliberate design (see summaries/260709-unitdefaults-os-temperature-preference.md):
        // WidgetStateManager.useCelsius() reads REGION from Resources.getSystem() and only the
        // explicit OS temperature preference (not language) from the app locale. Robolectric's
        // RuntimeEnvironment.setQualifiers moves app-context AND Resources.getSystem() together
        // (verified empirically — they are not independently controllable that way), so this
        // test mutates the app Context's Configuration directly, leaving Resources.getSystem()
        // untouched — the same split the real per-app LocaleManager produces on-device.
        val context = app()
        assertEquals(
            "Resources.getSystem() must start at the US test default for this test to be meaningful",
            "US",
            Resources.getSystem().configuration.locales[0].country,
        )

        val appConfig = Configuration(context.resources.configuration)
        appConfig.setLocale(Locale.GERMANY) // no -u-mu- extension: exercises the region fallback rung
        context.resources.updateConfiguration(appConfig, context.resources.displayMetrics)

        assertEquals("de_DE", context.resources.configuration.locales[0].toString())
        assertEquals(
            "Resources.getSystem() must be unaffected by the app-context locale mutation above",
            "US",
            Resources.getSystem().configuration.locales[0].country,
        )

        val stateManager = WidgetStateManager(context)
        assertFalse(
            "German app language must NOT flip units to Celsius while device region is US",
            stateManager.useCelsius(),
        )
    }
}
