package com.weatherwidget.ui

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsPointUnavailableException
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.WeatherApiCredentialProvider
import com.weatherwidget.shared.util.NwsCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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

object SetupSourcePolicy {
    fun sourcesAfterSetupCheck(
        current: List<WeatherSource>,
        nwsCoverage: SetupNwsCoverage,
        weatherApiAvailable: Boolean,
        nwsAutoRetired: Boolean = false,
    ): List<WeatherSource> {
        // Back inside coverage after an automatic retirement: put NWS back where it was. A user
        // who unticked NWS themselves (flag false) keeps their choice.
        if (nwsCoverage == SetupNwsCoverage.SUPPORTED && nwsAutoRetired && WeatherSource.NWS !in current) {
            return NwsCoverage.restoreNws(current.map { it.id }).map { WeatherSource.fromId(it) }
        }
        if (nwsCoverage != SetupNwsCoverage.UNSUPPORTED || WeatherSource.NWS !in current) {
            return current
        }

        val result = NwsCoverage.retireNws(current.map { it.id })
            .map { WeatherSource.fromId(it) }
            .toMutableList()
        if (weatherApiAvailable && WeatherSource.WEATHER_API !in result) {
            result += WeatherSource.WEATHER_API
        }
        return result
    }
}

@Singleton
class SetupSourceAvailabilityChecker
    @Inject
    constructor(
        private val nwsApi: NwsApi,
        private val weatherApi: WeatherApi,
    ) {
        suspend fun checkNws(
            latitude: Double,
            longitude: Double,
        ): Pair<SetupNwsCoverage, String?> =
            try {
                withTimeout(NWS_TIMEOUT_MS) {
                    nwsApi.getGridPoint(latitude, longitude)
                }
                SetupNwsCoverage.SUPPORTED to null
            } catch (e: NwsPointUnavailableException) {
                SetupNwsCoverage.UNSUPPORTED to "invalid_point"
            } catch (e: TimeoutCancellationException) {
                inconclusive(latitude, longitude, "timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiAccessException) {
                inconclusive(latitude, longitude, "http_${e.statusCode ?: "unknown"}")
            } catch (e: IOException) {
                inconclusive(latitude, longitude, "network")
            } catch (e: Exception) {
                inconclusive(latitude, longitude, "error_${e.javaClass.simpleName}")
            }

        /**
         * The probe could not answer. Inside the coverage box that stays INCONCLUSIVE (keep NWS,
         * it very likely works); outside it the box is authoritative enough to retire NWS — a
         * location set while offline must not keep a source that can never load there.
         */
        private fun inconclusive(
            latitude: Double,
            longitude: Double,
            reason: String,
        ): Pair<SetupNwsCoverage, String?> =
            if (NwsCoverage.covers(latitude, longitude)) {
                SetupNwsCoverage.INCONCLUSIVE to reason
            } else {
                SetupNwsCoverage.UNSUPPORTED to "${reason}_outside_coverage_box"
            }

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
            const val NWS_TIMEOUT_MS = 5_000L
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
        suspend fun select(
            current: List<WeatherSource>,
            latitude: Double,
            longitude: Double,
            nwsAutoRetired: Boolean = false,
        ): SetupSourceSelection {
            val (nwsCoverage, nwsReason) = checker.checkNws(latitude, longitude)
            if (nwsCoverage != SetupNwsCoverage.UNSUPPORTED || WeatherSource.NWS !in current) {
                val restored = SetupSourcePolicy.sourcesAfterSetupCheck(
                    current = current,
                    nwsCoverage = nwsCoverage,
                    weatherApiAvailable = false,
                    nwsAutoRetired = nwsAutoRetired,
                )
                return SetupSourceSelection(
                    sources = restored,
                    nwsCoverage = nwsCoverage,
                    reason = if (restored !== current) "nws_restored" else nwsReason,
                )
            }

            if (WeatherSource.WEATHER_API in current) {
                return SetupSourceSelection(
                    sources = SetupSourcePolicy.sourcesAfterSetupCheck(
                        current = current,
                        nwsCoverage = nwsCoverage,
                        weatherApiAvailable = true,
                    ),
                    nwsCoverage = nwsCoverage,
                    weatherApiAvailability = SetupWeatherApiAvailability.ALREADY_ENABLED,
                    reason = nwsReason,
                )
            }

            if (!weatherApiCredentialProvider.isConfigured()) {
                return SetupSourceSelection(
                    sources = SetupSourcePolicy.sourcesAfterSetupCheck(
                        current = current,
                        nwsCoverage = nwsCoverage,
                        weatherApiAvailable = false,
                    ),
                    nwsCoverage = nwsCoverage,
                    weatherApiAvailability = SetupWeatherApiAvailability.MISSING_KEY,
                    reason = "missing_key",
                )
            }

            val (weatherApiAvailability, weatherApiReason) =
                checker.checkWeatherApi(latitude, longitude)
            return SetupSourceSelection(
                sources = SetupSourcePolicy.sourcesAfterSetupCheck(
                    current = current,
                    nwsCoverage = nwsCoverage,
                    weatherApiAvailable = weatherApiAvailability == SetupWeatherApiAvailability.AVAILABLE,
                ),
                nwsCoverage = nwsCoverage,
                weatherApiAvailability = weatherApiAvailability,
                reason = weatherApiReason ?: nwsReason,
            )
        }
    }
