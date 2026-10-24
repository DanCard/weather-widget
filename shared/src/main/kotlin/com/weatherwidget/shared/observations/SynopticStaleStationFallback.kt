package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.SynopticApi

object SynopticStaleStationFallback {
    fun wrap(
        primary: NwsStationLatestFetcher.WebSource?,
        synopticApi: SynopticApi?,
        latitude: Double,
        longitude: Double,
        nowMs: () -> Long = System::currentTimeMillis,
        log: suspend (tag: String, message: String, level: String) -> Unit = { _, _, _ -> },
    ): NwsStationLatestFetcher.WebSource? {
        if (synopticApi == null) return primary
        return NwsStationLatestFetcher.WebSource(
            transport = primary?.transport?.plus("+synoptic_stale") ?: "synoptic_stale",
            elapsedMs = primary?.elapsedMs ?: { null },
        ) { station, index, apiNewestMs ->
            val primaryOutcome = primary?.rows?.invoke(station, index, apiNewestMs) ?: FetchOutcome.NoData
            val primaryRows = primaryOutcome.valueOrNull().orEmpty()
            val newestMs = listOfNotNull(apiNewestMs, primaryRows.maxOfOrNull { it.timestamp }).maxOrNull()
            if (!ObservationFallbackPolicy.shouldUseWebFallback(index, newestMs, nowMs())) {
                return@WebSource primaryOutcome
            }

            val recentMinutes = ObservationFallbackPolicy.webFallbackWindowMinutes(newestMs, nowMs())
            val startedMs = System.currentTimeMillis()
            val fallback = when (
                val outcome = synopticApi.fetchSynopticObservations(
                    stationId = station.id,
                    recentMinutes = recentMinutes,
                    stationNameFallback = station.name,
                )
            ) {
                is FetchOutcome.Success -> FetchOutcome.Success(
                    outcome.value.map {
                        NwsObservationMapper.toReading(
                            it, station, latitude, longitude,
                            isWebFallback = true,
                        )
                    },
                )
                FetchOutcome.NoData -> FetchOutcome.NoData
                is FetchOutcome.Failed -> outcome
            }
            val outcomeLabel = when (fallback) {
                is FetchOutcome.Success -> "success"
                FetchOutcome.NoData -> "no_data"
                is FetchOutcome.Failed -> "failed:${fallback.reason}"
            }
            log(
                "OBS_SYNOPTIC_FALLBACK",
                "station=${station.id} index=$index outcome=$outcomeLabel recentMin=$recentMinutes " +
                    "rows=${fallback.valueOrNull()?.size ?: 0} elapsedMs=${System.currentTimeMillis() - startedMs}",
                "INFO",
            )
            val primaryNewest = primaryRows.maxOfOrNull { it.timestamp }
            val fallbackNewest = fallback.valueOrNull()?.maxOfOrNull { it.timestamp }
            val useFallback = fallback is FetchOutcome.Success &&
                fallback.value.isNotEmpty() &&
                (primaryNewest == null || (fallbackNewest != null && fallbackNewest > primaryNewest))
            if (useFallback) fallback else primaryOutcome
        }
    }
}
