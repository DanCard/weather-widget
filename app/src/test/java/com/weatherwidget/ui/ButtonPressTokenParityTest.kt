package com.weatherwidget.ui

import com.weatherwidget.shared.util.WeatherThemeTokens
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android resource XML can't read Kotlin constants, so the press animation and disabled dim are
 * written out in `res/`. This pins those literals to [WeatherThemeTokens], which desktop
 * `WeatherButtons.kt` reads, so the two platforms can't drift apart (plans/261113-button-press-feedback.md).
 */
@Category(ShortDuration::class)
class ButtonPressTokenParityTest {
    private val resDir = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(resDir, path)).documentElement

    private fun Element.attr(name: String) = getAttributeNS(androidNs, name)

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun scaleAnimators(item: Element) =
        item.children("objectAnimator").filter { it.attr("propertyName") in setOf("scaleX", "scaleY") }

    @Test
    fun `press animators match the shared press tokens`() {
        for (file in listOf("animator/button_press_scale.xml", "animator/button_press_scale_flat.xml")) {
            val items = parse(file).childNodes.let { n -> (0 until n.length).map { n.item(it) } }
                .filterIsInstance<Element>().filter { it.tagName == "item" }
            val pressed = items.single { it.attr("state_pressed") == "true" }
            val released = items.single { it.attr("state_enabled") == "true" && it.attr("state_pressed").isEmpty() }

            val down = scaleAnimators(pressed)
            assertEquals("$file pressed must animate scaleX and scaleY", 2, down.size)
            down.forEach {
                assertEquals(file, WeatherThemeTokens.BUTTON_PRESS_SCALE, it.attr("valueTo").toFloat(), 0.0001f)
                assertEquals(file, WeatherThemeTokens.BUTTON_PRESS_IN_MS, it.attr("duration").toInt())
            }
            val up = scaleAnimators(released)
            assertEquals("$file release must animate scaleX and scaleY", 2, up.size)
            up.forEach {
                assertEquals(file, 1f, it.attr("valueTo").toFloat(), 0.0001f)
                assertEquals(file, WeatherThemeTokens.BUTTON_PRESS_OUT_MS, it.attr("duration").toInt())
                // Framework overshoot_interpolator has tension 2.0 == BUTTON_PRESS_OUT_OVERSHOOT_TENSION.
                assertEquals(file, "@android:anim/overshoot_interpolator", it.attr("interpolator"))
            }
        }
        assertEquals(2.0f, WeatherThemeTokens.BUTTON_PRESS_OUT_OVERSHOOT_TENSION, 0f)
    }

    @Test
    fun `every color button ripples and dims its own fill when disabled`() {
        val expectedAlpha = Math.round(WeatherThemeTokens.BUTTON_DISABLED_ALPHA * 255)
        val files = File(resDir, "drawable").listFiles { f -> f.name.startsWith("rounded_button_") }!!.sortedBy { it.name }
        assertTrue("expected the five rounded_button_* drawables, found ${files.map { it.name }}", files.size >= 5)
        for (f in files) {
            val root = parse("drawable/${f.name}")
            assertEquals("${f.name} must be a ripple", "ripple", root.tagName)
            val selectorItems = root.children("selector").single().children("item")
            val disabled = selectorItems.single { it.attr("state_enabled") == "false" }
                .children("solid").single().attr("color")
            val enabled = selectorItems.single { it.attr("state_enabled").isEmpty() }
                .children("solid").single().attr("color")
            println("ButtonPressTokenParity: ${f.name} enabled=$enabled disabled=$disabled")
            assertEquals("${f.name} disabled alpha", expectedAlpha, disabled.substring(1, 3).toInt(16))
            assertEquals("${f.name} disabled must be the same hue as enabled", enabled.takeLast(6), disabled.takeLast(6))
        }
    }
}
