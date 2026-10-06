package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.LocationChangePaintPolicy
import java.time.LocalDate

/**
 * Desktop half of a source becoming primary (Android: `SourceSwitchFetch`). The popup announces
 * "Getting weather from {source}…" over the new source's graph until its first fetch ends — unless that
 * source already has a drawable cache (today's daily row and today's hourly rows, the same bar a
 * location change uses), when there is nothing to wait for.
 * See plans/261006-source-becomes-primary-fetch-and-banner.md.
 */
internal object DesktopSourceSwitchFeedback {
    fun hasDrawableCache(cached: ForecastSnapshot?, today: LocalDate): Boolean =
        cached != null &&
            LocationChangePaintPolicy.hasDrawableCache(cached.raw.daily, cached.raw.hourly.map { it.dateTime }, today)

    fun fetchingMessage(source: WeatherSource): String = "Getting weather from ${source.displayName}…"

    fun failedMessage(source: WeatherSource): String = "Couldn’t get weather from ${source.displayName}"
}
