package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopUiArchitectureTest {
    private val sourceRoot by lazy(::findDesktopSourceRoot)

    @Test
    fun `Main is a thin process entrypoint`() {
        val mainSource = sourceRoot.resolve("Main.kt").readText()
        assertTrue(
            "Main.kt should remain a thin entrypoint under 100 lines, was ${mainSource.lineSequence().count()}",
            mainSource.lineSequence().count() <= 100,
        )
        assertFalse("Main.kt must not contain inline WidgetPopup composable", "fun WidgetPopup(" in mainSource)
        assertFalse("Main.kt must not contain inline WidgetHeader composable", "fun WidgetHeader(" in mainSource)
        assertFalse("Main.kt must not contain runDesktopUiApplication implementation", "fun runDesktopUiApplication(" in mainSource)
        assertFalse("Main.kt must not contain dayClickConfig", "fun dayClickConfig(" in mainSource)
    }

    @Test
    fun `desktop ui modules own their respective responsibilities`() {
        val uiApp = sourceRoot.resolve("DesktopUiApplication.kt").readText()
        val popup = sourceRoot.resolve("DesktopWidgetPopup.kt").readText()
        val header = sourceRoot.resolve("DesktopWidgetHeader.kt").readText()
        val hosts = sourceRoot.resolve("DesktopWindowHosts.kt").readText()
        val nav = sourceRoot.resolve("DesktopDayClickNavigation.kt").readText()

        assertTrue("DesktopUiApplication must host the UI application root", "fun runDesktopUiApplication(" in uiApp)
        assertTrue("DesktopWidgetPopup must own WidgetPopup composable", "fun WidgetPopup(" in popup)
        assertTrue("DesktopWidgetHeader must own WidgetHeader composable", "fun WidgetHeader(" in header)
        assertTrue("DesktopWindowHosts must own PopupWindowHost", "fun PopupWindowHost(" in hosts)
        assertTrue("DesktopWindowHosts must own SettingsWindowHost", "fun SettingsWindowHost(" in hosts)
        assertTrue("DesktopWindowHosts must own LocationPickerWindowHost", "fun LocationPickerWindowHost(" in hosts)
        assertTrue("DesktopDayClickNavigation must own dayClickConfig", "fun dayClickConfig(" in nav)
    }

    private fun findDesktopSourceRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin/com/weatherwidget/desktop"),
            File("desktop/src/main/kotlin/com/weatherwidget/desktop"),
        )
        return requireNotNull(candidates.firstOrNull { it.isDirectory }) {
            "Could not locate desktop main source root from ${File(".").absolutePath}"
        }
    }
}
