package com.weatherwidget.widget.handlers

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ART must accept the widget's paint classes. Their `updateWidget` suspend functions are very large,
 * and on 2026-11-19 the dex for `CloudCoverViewHandler.updateWidget` was rejected by the verifier
 * (`VerifyError: ... copy-cat2 v3<-v129 type=Long (Low Half)/Zero/null`). A class is verified only
 * when first loaded, so the suite crashed only in runs that happened to paint the cloud view — and a
 * device would crash the first time the user opened it.
 *
 * Loading and initializing each class here turns a rejection into a plain failure of this test on
 * every run. Only a real device's ART can verify dex, hence instrumented.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHandlerClassVerificationInstrumentedTest {
    private val classes = listOf(
        "com.weatherwidget.widget.handlers.CloudCoverViewHandler",
        "com.weatherwidget.widget.handlers.DailyViewHandler",
        "com.weatherwidget.widget.handlers.PrecipViewHandler",
        "com.weatherwidget.widget.handlers.TemperatureViewHandler",
        "com.weatherwidget.widget.handlers.WidgetViewHandler",
        "com.weatherwidget.widget.WidgetRenderer",
        "com.weatherwidget.widget.WidgetStartupCoordinator",
        // Split 2026-11-19 (plans/261119-split-oversized-android-suspend-functions.md).
        "com.weatherwidget.widget.FullSyncPipeline",
        "com.weatherwidget.widget.handlers.TemperatureStateResolver",
        // Phase 2 of the same plan.
        "com.weatherwidget.widget.handlers.DailyInteractionRenderer",
        "com.weatherwidget.widget.handlers.DailyGraphRenderer",
        "com.weatherwidget.widget.handlers.TemperatureGraphHoursLoader",
        "com.weatherwidget.widget.WeatherWidgetWorker",
        "com.weatherwidget.data.repository.ForecastRepository",
        "com.weatherwidget.shared.actuals.DailyBlendRecomputeRun",
        "com.weatherwidget.shared.observations.CurrentConditionsRun",
        "com.weatherwidget.shared.graph.TemperatureExtrema",
    )

    @Test
    fun everyWidgetPaintClassPassesArtVerification() {
        val loader = javaClass.classLoader
        val rejected = classes.mapNotNull { name ->
            try {
                Class.forName(name, true, loader)
                println("WidgetHandlerClassVerification: verified $name")
                null
            } catch (e: VerifyError) {
                println("WidgetHandlerClassVerification: REJECTED $name: ${e.message}")
                "$name: ${e.message?.take(300)}"
            }
        }
        assertTrue("ART rejected:\n${rejected.joinToString("\n")}", rejected.isEmpty())
    }
}
