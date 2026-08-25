package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.Localization
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the failure-watermark text to Android resources under a non-English locale, so the
 * hardcoded "UPDATES FAILING" headline and English error-code phrases cannot silently return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
@Category(Localization::class)
class WatermarkLocalizationRoboTest {

    @Test
    fun `watermark headline and error codes resolve localized`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("AKTUALISIERUNGEN FEHLGESCHLAGEN", context.getString(R.string.updates_failing))
        assertEquals("429 Limit erreicht", GraphFailureWatermarkRenderer.localizedErrorCodeText(context, "HTTP_429"))
        assertEquals("Zeitüberschreitung", GraphFailureWatermarkRenderer.localizedErrorCodeText(context, "TIMEOUT"))
        assertEquals("502 Serverfehler", GraphFailureWatermarkRenderer.localizedErrorCodeText(context, "HTTP_502"))
    }
}
