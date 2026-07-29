package com.weatherwidget.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.di.RepositoryEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in emulator proof against the real production endpoints. The normal instrumented suite
 * skips it so offline CI does not become network-dependent. Run with
 * `-e liveSetupSourceCheck true`.
 */
@RunWith(AndroidJUnit4::class)
class SetupSourceSelectorLiveTest {
    @Test
    fun londonDisablesNwsAndEnablesValidatedWeatherApi() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue(
                "Live setup source check is opt-in",
                InstrumentationRegistry.getArguments().getString("liveSetupSourceCheck") == "true",
            )
            val context = instrumentation.targetContext.applicationContext
            val selector =
                EntryPointAccessors.fromApplication(
                    context,
                    RepositoryEntryPoint::class.java,
                ).setupSourceSelector()

            val result =
                selector.select(
                    current =
                        listOf(
                            WeatherSource.NWS,
                            WeatherSource.OPEN_METEO,
                            WeatherSource.SILURIAN,
                        ),
                    latitude = 51.5074,
                    longitude = -0.1278,
                )

            assertEquals(SetupNwsCoverage.UNSUPPORTED, result.nwsCoverage)
            assertEquals(
                SetupWeatherApiAvailability.AVAILABLE,
                result.weatherApiAvailability,
            )
            assertEquals(
                listOf(
                    WeatherSource.OPEN_METEO,
                    WeatherSource.SILURIAN,
                    WeatherSource.WEATHER_API,
                ),
                result.sources,
            )
        }
}
