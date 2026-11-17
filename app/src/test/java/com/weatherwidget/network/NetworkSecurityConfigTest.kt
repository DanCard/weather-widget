package com.weatherwidget.network

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@Category(ShortDuration::class)
class NetworkSecurityConfigTest {
    private fun appFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app/$relativePath"))
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath from ${File(".").absolutePath}")
    }

    @Test
    fun `manifest wires network_security_config and cleartext is scoped strictly to Open-Meteo`() {
        val manifestText = appFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must declare android:networkSecurityConfig",
            manifestText.contains("""android:networkSecurityConfig="@xml/network_security_config""""),
        )

        val xmlFile = appFile("src/main/res/xml/network_security_config.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val baseConfigs = doc.getElementsByTagName("base-config")
        assertEquals(1, baseConfigs.length)
        val basePermitted = (baseConfigs.item(0) as Element).getAttribute("cleartextTrafficPermitted")
        assertEquals("false", basePermitted)

        val domainConfigs = doc.getElementsByTagName("domain-config")
        assertEquals(1, domainConfigs.length)
        val domainConfig = domainConfigs.item(0) as Element
        assertEquals("true", domainConfig.getAttribute("cleartextTrafficPermitted"))

        val domains = domainConfig.getElementsByTagName("domain")
        val permittedHosts = (0 until domains.length).map { i ->
            val el = domains.item(i) as Element
            assertEquals("false", el.getAttribute("includeSubdomains"))
            el.textContent.trim()
        }.toSet()
        assertEquals(setOf("api.open-meteo.com", "previous-runs-api.open-meteo.com"), permittedHosts)
    }
}
