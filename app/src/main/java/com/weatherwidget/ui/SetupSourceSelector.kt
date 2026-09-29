package com.weatherwidget.ui

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.WeatherApiCredentialProvider
import com.weatherwidget.shared.util.NwsCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Coverage-box result, for the setup log line. INCONCLUSIVE only when the selector itself threw. */
enum class SetupNwsCoverage {
    SUPPORTED,
    UNSUPPORTED,
    INCONCLUSIVE,
}

enum class SetupWeatherApiAvailability {
    NOT_CHECKED,
    ALREADY_ENABLED,
    MISSING_KEY,
    AVAILABLE,
    UNAVAILABLE,
}

data class SetupSourceSelection(
    val sources: List<WeatherSource>,
    val nwsCoverage: SetupNwsCoverage,
    val weatherApiAvailability: SetupWeatherApiAvailability = SetupWeatherApiAvailability.NOT_CHECKED,
    val reason: String? = null,
)

/**
 * The setup screen's only edit to the enabled list: outside NWS coverage, add WeatherAPI when it is
 * available and not already there. NWS itself is never added or removed here — whether it can
 * serve a site is derived per location by `SourceCoverage` and never written to the list
 * (2026-09-29: a lost "the app removed NWS" marker left it off for good).
 */
object SetupSourcePolicy {
    fun sourcesAfterSetupCheck(
        current: List<WeatherSource>,
        weatherApiAvailable: Boolean,
    ): List<WeatherSource> =
        if (weatherApiAvailable && WeatherSource.WEATHER_API !in current) current + WeatherSource.WEATHER_API else current
}

@Singleton
class SetupSourceAvailabilityChecker
    @Inject
    constructor(
        private val weatherApi: WeatherApi,
    ) {
        suspend fun checkWeatherApi(
            latitude: Double,
            longitude: Double,
        ): Pair<SetupWeatherApiAvailability, String?> =
            try {
                val forecast = withTimeout(WEATHER_API_TIMEOUT_MS) {
                    weatherApi.getForecast(latitude, longitude, days = 1)
                }
                if (forecast.daily.isNotEmpty()) {
                    SetupWeatherApiAvailability.AVAILABLE to null
                } else {
                    SetupWeatherApiAvailability.UNAVAILABLE to "empty_forecast"
                }
            } catch (e: TimeoutCancellationException) {
                SetupWeatherApiAvailability.UNAVAILABLE to "timeout"
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiAccessException) {
                SetupWeatherApiAvailability.UNAVAILABLE to "http_${e.statusCode ?: "unknown"}"
            } catch (e: IOException) {
                SetupWeatherApiAvailability.UNAVAILABLE to "network"
            } catch (e: Exception) {
                SetupWeatherApiAvailability.UNAVAILABLE to "error_${e.javaClass.simpleName}"
            }

        companion object {
            const val WEATHER_API_TIMEOUT_MS = 5_000L
        }
    }

@Singleton
class SetupSourceSelector
    @Inject
    constructor(
        private val checker: SetupSourceAvailabilityChecker,
        private val weatherApiCredentialProvider: WeatherApiCredentialProvider,
    ) {
        /**
         * [SetupSourceSelection.nwsCoverage] is the coverage box ([NwsCoverage.covers]) — the same
         * test that decides whether NWS is usable at the site. The live `/points` probe this used to
         * run (~1 s per save) only fed the NWS remove/restore decision, which no longer exists.
         *
         * WeatherAPI is offered as NWS's stand-in only when a save *leaves* coverage with NWS
         * enabled ([previous] covered, or no previous site). It used to fire once because it also
         * removed NWS; with NWS kept enabled, firing on every save outside coverage would re-add a
         * WeatherAPI the user had unticked.
         */
        suspend fun select(
            current: List<WeatherSource>,
            latitude: Double,
            longitude: Double,
            previous: Pair<Double, Double>? = null,
        ): SetupSourceSelection {
            if (NwsCoverage.covers(latitude, longitude)) {
                return SetupSourceSelection(sources = current, nwsCoverage = SetupNwsCoverage.SUPPORTED)
            }
            val leavingCoverage = previous == null || NwsCoverage.covers(previous.first, previous.second)
            if (WeatherSource.NWS !in current || !leavingCoverage) {
                return SetupSourceSelection(
                    sources = current,
                    nwsCoverage = SetupNwsCoverage.UNSUPPORTED,
                    reason = if (WeatherSource.NWS !in current) "nws_not_enabled" else "already_outside_coverage",
                )
            }
            if (WeatherSource.WEATHER_API in current) {
                return SetupSourceSelection(
                    sources = current,
                    nwsCoverage = SetupNwsCoverage.UNSUPPORTED,
                    weatherApiAvailability = SetupWeatherApiAvailability.ALREADY_ENABLED,
                    reason = "outside_coverage_box",
                )
            }
            if (!weatherApiCredentialProvider.isConfigured()) {
                return SetupSourceSelection(
                    sources = current,
                    nwsCoverage = SetupNwsCoverage.UNSUPPORTED,
                    weatherApiAvailability = SetupWeatherApiAvailability.MISSING_KEY,
                    reason = "missing_key",
                )
            }
            val (weatherApiAvailability, weatherApiReason) = checker.checkWeatherApi(latitude, longitude)
            return SetupSourceSelection(
                sources = SetupSourcePolicy.sourcesAfterSetupCheck(
                    current = current,
                    weatherApiAvailable = weatherApiAvailability == SetupWeatherApiAvailability.AVAILABLE,
                ),
                nwsCoverage = SetupNwsCoverage.UNSUPPORTED,
                weatherApiAvailability = weatherApiAvailability,
                reason = weatherApiReason ?: "outside_coverage_box",
            )
        }
    }
