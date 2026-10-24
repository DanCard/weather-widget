package com.weatherwidget.ui

import com.weatherwidget.shared.rainmap.RainLoopLoadError
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Each `:shared` [RainLoopLoadError.Line] template (desktop's text) equals Android's English
 * `sim_loop_error_<line>` resource, placeholders included — so both platforms fill the same line with
 * the same arguments. Pure JVM: parses `values/strings.xml` directly.
 */
@Category(ShortDuration::class)
class RainLoopErrorLineParityTest {
    private val strings: Map<String, String> by lazy {
        val resDir = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "values/strings.xml"))
        val nodes = doc.documentElement.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'").replace("\\\"", "\"") }
    }

    @Test
    fun `every shared rain-loop error line matches its English resource`() {
        for (line in RainLoopLoadError.Line.entries) {
            val name = "sim_loop_error_${line.name.lowercase()}"
            val resource = strings[name]
            assertNotNull("missing string resource $name", resource)
            assertEquals(name, resource, line.english)
        }
    }
}
