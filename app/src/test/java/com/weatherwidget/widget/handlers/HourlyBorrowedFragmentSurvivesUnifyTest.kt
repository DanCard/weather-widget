package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Integration regression across [HourlyForecastStitcher] and [GraphDataLoader]: the two layers that
 * disagreed about borrowed rows, exercised in the same order the render path runs them.
 *
 * Seeded from the measured shape of the 2026-09-03 bug report (Samsung Fold, widget 345, NWS):
 *
 *  * configured centre `37.42414855957031,-122.08828735351562`
 *  * winning site `37.424,-122.088` — NWS rows for **11:00-23:00 only**. The 08-27 fetch's ~156h
 *    horizon ended 09-02 23:00 and the next fetch at that site was 11:59, so hours 00:00-10:00 were
 *    never written there, in `hourly_forecasts` or `hourly_forecast_history`.
 *  * fragment `37.417,-122.089` (0.0072 deg away, from the 01:26 fetch) — the ONLY coverage of
 *    01:00-12:00.
 *
 * The stitcher borrowed the fragment for the uncovered hours; `unifyToNearestSite` then kept only
 * rows within 0.002 deg of the nearest site and deleted them again. The cloud graph logged
 * `CLOUD_COVER_GAPS missing=7 total=19 ranges=4a-10a` and simply ended mid-curve — indistinguishable
 * from the provider never having published those hours.
 *
 * See plans/260903-unify-must-keep-hours-the-nearest-site-cannot-cover.md.
 */
@Category(ShortDuration::class)
class HourlyBorrowedFragmentSurvivesUnifyTest {

    private companion object {
        /** 2026-09-03 00:00 as a fixed epoch; only hour arithmetic matters. */
        const val MIDNIGHT = 1_788_418_800_000L
        const val ONE_HOUR = 3_600_000L

        const val CENTER_LAT = 37.42414855957031
        const val CENTER_LON = -122.08828735351562

        // The site the configured centre resolves to; covers 11:00 onward only.
        const val SITE_LAT = 37.424
        const val SITE_LON = -122.088

        // 0.0072 deg away: outside sameSite (0.002), inside the nearby fallback (0.01).
        const val FRAGMENT_LAT = 37.417
        const val FRAGMENT_LON = -122.089
    }

    private fun row(hour: Int, lat: Double, lon: Double, cloud: Int, fetchedAt: Long) =
        HourlyForecastEntity(
            dateTime = MIDNIGHT + hour * ONE_HOUR,
            locationLat = lat,
            locationLon = lon,
            temperature = 60f + hour,
            condition = "Cloudy",
            source = "NWS",
            cloudCover = cloud,
            fetchedAt = fetchedAt,
        )

    /** The render path: stitch the raw proximity-box rows, then collapse to a site. */
    private fun renderRows(
        current: List<HourlyForecastEntity>,
        history: List<HourlyForecastEntity>,
    ): List<HourlyForecastEntity> {
        val stitched = HourlyForecastStitcher.stitch(
            current = current.map { it.toHourlyForecast() },
            history = history.map { it.toHourlyForecast() },
            nowMs = MIDNIGHT + 13 * ONE_HOUR,
            centerLat = CENTER_LAT,
            centerLon = CENTER_LON,
        ).map { it.toEntity(CENTER_LAT, CENTER_LON) }
        return GraphDataLoader.unifyToNearestSite(stitched, CENTER_LAT, CENTER_LON)
    }

    @Test
    fun hoursOnlyTheJitterFragmentCoversReachTheRenderList() {
        // 11:59 and 13:20 fetches at the configured site: 11:00 onward.
        val atSite = (11..19).map { row(it, SITE_LAT, SITE_LON, cloud = 20, fetchedAt = 1_000L) }
        // 01:26 fetch on the jitter fragment: 01:00-12:00, the only coverage of the morning.
        val onFragment = (1..12).map { row(it, FRAGMENT_LAT, FRAGMENT_LON, cloud = 55, fetchedAt = 500L) }

        val rendered = renderRows(current = atSite + onFragment, history = emptyList())
        val hours = rendered.map { ((it.dateTime - MIDNIGHT) / ONE_HOUR).toInt() }

        assertEquals(
            "the 4a-10a hours the report lost must reach the render list",
            (1..19).toList(),
            hours,
        )
        // The exact seven hours named in CLOUD_COVER_GAPS.
        assertTrue(
            "hours 4-10 must carry the fragment's cloud value, not be dropped",
            (4..10).all { h ->
                rendered.single { it.dateTime == MIDNIGHT + h * ONE_HOUR }.cloudCover == 55
            },
        )
    }

    @Test
    fun renderListStaysAtOneSiteSoDownstreamCannotAdoptTheDonor() {
        // A downstream firstOrNull() adopting a borrowed row's coordinates as the render location is
        // what centred the observation blend three hours in the past on 2026-08-28.
        val atSite = (11..19).map { row(it, SITE_LAT, SITE_LON, cloud = 20, fetchedAt = 1_000L) }
        val onFragment = (1..12).map { row(it, FRAGMENT_LAT, FRAGMENT_LON, cloud = 55, fetchedAt = 500L) }

        val rendered = renderRows(current = atSite + onFragment, history = emptyList())

        assertEquals(
            "render rows must all report one site",
            1,
            rendered.map { it.locationLat to it.locationLon }.distinct().size,
        )
        assertEquals(SITE_LAT to SITE_LON, rendered.first().locationLat to rendered.first().locationLon)
    }

    @Test
    fun theWinningSiteStillWinsEveryHourItCovers() {
        // Borrowing must never reach an hour both cover — that is the DailyNoonCloudCover flap this
        // collapse exists to prevent, and the fragment here is both nearer-in-time and staler.
        val atSite = (1..19).map { row(it, SITE_LAT, SITE_LON, cloud = 20, fetchedAt = 9_000L) }
        val onFragment = (1..12).map { row(it, FRAGMENT_LAT, FRAGMENT_LON, cloud = 55, fetchedAt = 500L) }

        val rendered = renderRows(current = atSite + onFragment, history = emptyList())

        assertEquals((1..19).toList(), rendered.map { ((it.dateTime - MIDNIGHT) / ONE_HOUR).toInt() })
        assertTrue(
            "every hour the site covers must keep the site's own value",
            rendered.all { it.cloudCover == 20 },
        )
    }

    @Test
    fun historySnapshotsBackfillTheSameUncoveredHours() {
        // The live table is REPLACE-overwritten, so on the device the morning hours often survive
        // only as history snapshots. Same borrow must apply through that leg.
        val atSite = (11..19).map { row(it, SITE_LAT, SITE_LON, cloud = 20, fetchedAt = 1_000L) }
        val historyOnFragment = (1..12).map { row(it, FRAGMENT_LAT, FRAGMENT_LON, cloud = 55, fetchedAt = 500L) }

        val rendered = renderRows(current = atSite, history = historyOnFragment)

        assertEquals((1..19).toList(), rendered.map { ((it.dateTime - MIDNIGHT) / ONE_HOUR).toInt() })
    }
}
