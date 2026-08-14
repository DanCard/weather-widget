package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Phase 4 item 5: the bug-report mailto: URI builder is a pure function, so it's a ShortDuration
 * JVM test (no Compose). Guards two contracts: the URI shape is well-formed enough to hand to
 * `openInBrowser`, and the diagnostic body never leaks API keys (they're redacted even if set).
 */
@Category(ShortDuration::class)
class BugReportMailtoTest {

    private val sampleConfig = DesktopConfig(
lat = 37.3861,
lon = -122.0839,
label = "Mountain View",
viewMode = ViewMode.DAILY,
settings = DesktopSettings(weatherSource = "NWS",
visibleSources = listOf("NWS", "OPEN_METEO"),
useCelsius = false,
personalStationDiscount = 95,
apiKeys = mapOf(
            "TOMORROW_IO" to "secret-tomorrow-key",
            "SILURIAN" to "secret-silurian-key",
        )),
)

    @Test
    fun mailtoIsWellFormedWithSubjectAndBody() {
        val mailto = buildBugReportMailto(sampleConfig)

        assertTrue("must start with mailto:", mailto.startsWith("mailto:"))
        assertTrue("must include subject param", mailto.contains("subject="))
        assertTrue("must include body param", mailto.contains("body="))
        // Subject/body values are URL-encoded; "+" is a valid encoding for space but we replaced
        // with %20 for cross-mail-client safety.
        assertFalse(
            "subject/body must not contain raw spaces (some mail clients reject them)",
            mailto.substringAfter("subject=").contains(" "),
        )
    }

    @Test
    fun diagnosticBodyIncludesConfigSummaryWithoutApiKeys() {
        val mailto = buildBugReportMailto(sampleConfig)
        val decodedBody = java.net.URLDecoder.decode(mailto.substringAfter("body="), "UTF-8")

        // Includes the useful diagnostic fields.
        assertTrue("body must include location label", decodedBody.contains("Mountain View"))
        assertTrue("body must include weatherSource", decodedBody.contains("weatherSource: NWS"))
        assertTrue("body must include visibleSources", decodedBody.contains("visibleSources: [NWS, OPEN_METEO]"))
        assertTrue("body must include OS line", decodedBody.contains("OS:"))

        // Redacts API keys at the key-name level so a support engineer knows which providers were
        // configured without seeing the secret.
        assertFalse(
            "API key VALUES must never appear in the diagnostic body",
            decodedBody.contains("secret-tomorrow-key") || decodedBody.contains("secret-silurian-key"),
        )
        assertTrue(
            "API key NAMES appear redacted so support can still see which providers were configured",
            decodedBody.contains("TOMORROW_IO=(redacted)") && decodedBody.contains("SILURIAN=(redacted)"),
        )
    }

    @Test
    fun emptyApiKeysRendersNoneRatherThanEmptyMap() {
        val mailto = buildBugReportMailto(sampleConfig.copy(settings = sampleConfig.settings.copy(apiKeys = emptyMap())))
        val decodedBody = java.net.URLDecoder.decode(mailto.substringAfter("body="), "UTF-8")

        assertTrue(
            "with no api keys, body shows the explicit '(none)' marker",
            decodedBody.contains("apiKeys: (none)"),
        )
    }
}
