package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the hourly cloud "missing data" diagnostic to Android resources under a non-English
 * locale, so the hardcoded English wording it previously used cannot silently return. Asserts
 * resource resolution (Robolectric has no font engine) via the now-exposed formatter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
@Category(Localization::class)
class CloudCoverLocalizationRoboTest {

    @Test
    fun `cloud missing-data diagnostic resolves localized instead of hardcoded English`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Wolkendaten nicht verfügbar", CloudCoverGraphRenderer.buildMissingDiagnosticText(context, 5, 5, null))
        assertEquals("Wolkendaten fehlen für 1 von 5 Std.", CloudCoverGraphRenderer.buildMissingDiagnosticText(context, 1, 5, null))
        assertEquals("Wolkendaten fehlen für 3 von 5 Std.", CloudCoverGraphRenderer.buildMissingDiagnosticText(context, 3, 5, null))
        assertEquals("Wolkendaten fehlen um 14 Uhr", CloudCoverGraphRenderer.buildMissingDiagnosticText(context, 1, 5, "14 Uhr"))
        assertEquals("Wolkendaten fehlen 14–16 Uhr (3 von 5 Std.)", CloudCoverGraphRenderer.buildMissingDiagnosticText(context, 3, 5, "14–16 Uhr"))
    }
}
