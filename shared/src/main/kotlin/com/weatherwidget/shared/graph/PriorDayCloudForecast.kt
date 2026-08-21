package com.weatherwidget.shared.graph

/**
 * The frozen "what was this hour forecast to be, ~24h before it happened" cloud-cover series.
 *
 * Why it exists: `hourly_forecasts` is latest-only and REPLACE-overwritten, and Open-Meteo is
 * fetched with `past_days`, so hours that have already elapsed get rewritten by later runs *after*
 * those runs assimilated observations. Measured at one site: 11:00 local read 100% across four
 * consecutive runs, then 70% at the 12:28 fetch and 50% at 14:50 — both downgrades landing after
 * the hour was over. That retro-corrected value is a perfectly good *actual*; what it is not is the
 * forecast, and painting it on the forecast line destroys the only record of what was predicted.
 *
 * So the cloud graph draws the retro-corrected live row as the **actual**, and this series — served
 * by Open-Meteo's Previous Runs API as `cloud_cover_previous_day1` — as the **forecast**.
 *
 * Rows are stored in `hourly_forecast_history`, whose stated purpose ("what each hour was
 * *predicted* to be, captured at a `timestampToGroupPredictions`") this matches exactly, under
 * [SOURCE_ID] rather than the real source id. That separation is load-bearing:
 * `HourlyForecastStitcher.stitch`'s history fallback picks the row with the greatest `fetchedAt`,
 * so a freshly-fetched row carrying a 24h-old prediction filed under `OPEN_METEO` could win as
 * "the latest forecast" for a past hour that has aged out of the live table — silently changing the
 * temperature and precipitation graphs. Under a distinct id that cannot happen.
 */
object PriorDayCloudForecast {

    /** Never a display source; never passed to a source-scoped loader. */
    const val SOURCE_ID = "OPEN_METEO_PRIOR24"

    /** The lead time [SOURCE_ID] rows represent, matching `DailySnapshotSelector.PRIOR_WINDOW_HOURS`. */
    const val LEAD_HOURS = 24L

    const val LEAD_MS = LEAD_HOURS * 3_600_000L

    /**
     * The `timestampToGroupPredictions` a row for [hourStartMs] is filed under: the nominal time the
     * prediction was made. Deterministic, so refetching the same hour REPLACEs in place rather than
     * accumulating duplicates.
     */
    fun predictionBucketFor(hourStartMs: Long): Long = hourStartMs - LEAD_MS
}
