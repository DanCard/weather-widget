package com.weatherwidget.widget

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.handlers.ErrorPillTouchTargetHelper
import com.weatherwidget.widget.handlers.SimLoopTouchTarget
import com.weatherwidget.widget.handlers.setupMonthsViewShortcut
import com.weatherwidget.widget.handlers.setupSettingsShortcut
import com.weatherwidget.widget.handlers.setupWeatherStationsShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guard for widget **activity** PendingIntents: their Intent flags may change only together with
 * their request code.
 *
 * `PendingIntent.getActivity(…, FLAG_UPDATE_CURRENT)` replaces only the extras of a PendingIntent
 * the launcher still holds; that record survives repaints and app updates, so a changed flag never
 * reaches an installed widget. On 2026-10-21 Observations gained FLAG_ACTIVITY_CLEAR_TASK and the
 * emulator still launched `flg=0x10000000` — the screen kept opening on the previous source
 * (plans/261021-history-shortcut-showed-previous-source.md). Robolectric reproduces that retention,
 * which is why this is checked here and not only on a device.
 *
 * If this failed because you changed a flag: give that intent a new `WidgetRequestCodes` base, add
 * the old base to [RETIRED_BASES], then update [PINNED]. If you added a `PendingIntent.getActivity`
 * to the widget: add it to [PINNED] and to [capturedIntents].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class WidgetActivityPendingIntentFlagsGuardTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val widgetId = 7

    private data class Pinned(val base: Int, val flags: Int)

    private companion object {
        val PINNED = mapOf(
            "setLocation" to Pinned(5100, Intent.FLAG_ACTIVITY_NEW_TASK),
            "settings" to Pinned(950, 0),
            "weatherStations" to Pinned(5700, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            "errorPill" to Pinned(5750, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            "monthsView" to Pinned(5800, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            "simLoop" to Pinned(5600, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        /** Bases whose old flags may still be held by installed launchers; never reuse them. */
        val RETIRED_BASES = setOf(800, 5400, 5500)
    }

    /** Records every click PendingIntent a setup function binds. */
    private class CapturingViews(pkg: String) : RemoteViews(pkg, R.layout.widget_weather) {
        val intents = mutableListOf<PendingIntent>()

        override fun setOnClickPendingIntent(viewId: Int, pendingIntent: PendingIntent?) {
            pendingIntent?.let { intents += it }
            super.setOnClickPendingIntent(viewId, pendingIntent)
        }
    }

    private fun captured(bind: (RemoteViews) -> Unit): PendingIntent {
        val views = CapturingViews(app.packageName)
        bind(views)
        val distinct = views.intents.distinct()
        assertEquals("expected one click PendingIntent, got ${distinct.size}", 1, distinct.size)
        return distinct.single()
    }

    private fun capturedIntents(): Map<String, PendingIntent> = mapOf(
        "setLocation" to WidgetRenderer.setLocationIntent(app, widgetId),
        "settings" to captured { setupSettingsShortcut(app, it, widgetId) },
        "weatherStations" to captured { setupWeatherStationsShortcut(app, it, widgetId) },
        "errorPill" to captured {
            ErrorPillTouchTargetHelper.setupErrorPillTouchTarget(app, it, widgetId, showErrorWatermark = true, sourceId = WeatherSource.NWS.id)
        },
        "monthsView" to captured { setupMonthsViewShortcut(app, it, widgetId, WeatherSource.NWS, 37.42, -122.08) },
        "simLoop" to captured {
            SimLoopTouchTarget.apply(
                context = app,
                views = it,
                appWidgetId = widgetId,
                pillPx = RectF(100f, 100f, 200f, 140f),
                plotBottomPx = 400f,
                bitmapWidthPx = 800,
                bitmapHeightPx = 500,
                widgetWidthDp = 400,
                widgetHeightDp = 250,
                location = 37.42 to -122.08,
            )
        },
    )

    @Test
    fun `activity PendingIntent flags change only with a new request code`() {
        val problems = mutableListOf<String>()
        capturedIntents().forEach { (name, pending) ->
            val shadow = shadowOf(pending)
            assertTrue("$name is not an activity PendingIntent", shadow.isActivity)
            val base = shadow.requestCode - widgetId * 10000
            val flags = shadow.savedIntent.flags
            val pinned = PINNED.getValue(name)
            when {
                base in RETIRED_BASES ->
                    problems += "$name uses retired base $base — installed launchers may still hold its old flags"
                base == pinned.base && flags != pinned.flags ->
                    problems += "$name flags changed (pinned 0x${pinned.flags.toString(16)}, now " +
                        "0x${flags.toString(16)}) under the same base $base: installed widgets keep the old " +
                        "flags. Move it to a new WidgetRequestCodes base, retire $base, then update PINNED."
                base != pinned.base || flags != pinned.flags ->
                    problems += "$name is now base $base flags 0x${flags.toString(16)}; update PINNED " +
                        "(and add ${pinned.base} to RETIRED_BASES if it moved)"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `every widget activity PendingIntent is pinned`() {
        val widgetRoot = listOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("com/weatherwidget/widget") }
            .firstOrNull { it.isDirectory }
        assertTrue("could not locate widget sources from ${File(".").absolutePath}", widgetRoot != null)
        val sites = widgetRoot!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { "PendingIntent.getActivity(" in it.value }
                    .map { "${file.name}:${it.index + 1}" }
            }
            .toList()
        assertEquals(
            "widget PendingIntent.getActivity call sites changed: $sites — add each new one to PINNED and capturedIntents()",
            PINNED.size,
            sites.size,
        )
    }

    @Test
    fun `retired bases are not reused by any WidgetRequestCodes constant`() {
        val source = listOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("com/weatherwidget/widget/handlers/WidgetRequestCodes.kt") }
            .first { it.isFile }
            .readText()
        val bases = Regex("""const val BASE_\w+ = (\d+)""").findAll(source).map { it.groupValues[1].toInt() }.toList()
        assertTrue("no BASE_ constants found — did WidgetRequestCodes change shape?", bases.size > 10)
        val reused = bases.filter { it in RETIRED_BASES }
        assertTrue("retired request-code base(s) reused: $reused", reused.isEmpty())
    }
}
