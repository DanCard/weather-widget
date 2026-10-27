package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.nowcast.NowcastSeries
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** What each source's fetch asks for, once for both platforms (`plans/261025-shared-source-fetch-table.md`). */
@Category(ShortDuration::class)
class SourceFetchTableTest {
    private companion object {
        const val LAT = 37.41681
        const val LON = -122.08912
        const val NOW = 1_792_951_200_000L
        const val HOUR = 3_600_000L
    }

    private val asked = mutableListOf<String>()

    private val nws: suspend (Double, Double, ForecastParts) -> RawFetch = { _, _, parts ->
        asked += "NWS parts=$parts"
        RawFetch()
    }

    private inner class FakeHost(override val calls: SourceFetchTable.Calls) : SourceFetchTable.Host {
        val logs = mutableListOf<Pair<String, String>>()
        val nowcasts = mutableListOf<NowcastSeries>()
        var needsHistory = true
        val storedReads = mutableListOf<List<Any>>()
        val stored = listOf(HourlyForecast(NOW, 60f, "Clear"))

        override suspend fun storedGoogleHours(latitude: Double, longitude: Double, startMs: Long, endMs: Long): List<HourlyForecast> {
            storedReads += listOf(latitude, longitude, startMs, endMs)
            return stored
        }

        override suspend fun googleNeedsHistory(latitude: Double, longitude: Double, nowMs: Long) = needsHistory

        override fun saveNowcast(latitude: Double, longitude: Double, series: NowcastSeries) { nowcasts += series }

        override suspend fun log(tag: String, message: String, level: String) { logs += tag to message }
    }

    private fun table(host: FakeHost) = SourceFetchTable(host)

    @Test
    fun `Open-Meteo asks for 7 history days and the planned parts, and keeps the reply's nowcast`() = runBlocking {
        val nowcast = NowcastSeries(emptyList(), NOW)
        val host = FakeHost(SourceFetchTable.Calls(nws = nws, openMeteo = { _, _, days, parts -> asked += "OM days=$days parts=$parts"; RawFetch(nowcast = nowcast) }))

        table(host).fetch(WeatherSource.OPEN_METEO, LAT, LON, SourceFetchTable.Request(parts = ForecastParts.HOURLY), NOW)

        assertEquals(listOf("OM days=7 parts=HOURLY"), asked)
        assertEquals(listOf(nowcast), host.nowcasts)
    }

    @Test
    fun `a forecast reply's provider current reading is not filed as an observation`() = runBlocking {
        val reply = RawFetch(providerCurrentTemp = 64f, providerCurrentObservedAt = NOW)
        val host = FakeHost(
            SourceFetchTable.Calls(
                nws = nws,
                openMeteo = { _, _, _, _ -> reply },
                openWeatherMap = { _, _ -> reply },
            ),
        )

        for (source in listOf(WeatherSource.OPEN_METEO, WeatherSource.OPEN_WEATHER_MAP)) {
            val fetched = table(host).fetch(source, LAT, LON, SourceFetchTable.Request(), NOW)
            assertEquals("$source", emptyList<Any>(), fetched.rawObservations)
        }
    }

    @Test
    fun `a source that cannot split asks for both parts, one that can asks for the plan's`() = runBlocking {
        val host = FakeHost(
            SourceFetchTable.Calls(
                nws = nws,
                openMeteo = { _, _, _, _ -> RawFetch() },
                silurian = { _, _, parts -> asked += "SILURIAN parts=$parts"; RawFetch() },
            ),
        )

        table(host).fetch(WeatherSource.SILURIAN, LAT, LON, SourceFetchTable.Request(parts = ForecastParts.DAILY), NOW)
        table(host).fetch(WeatherSource.NWS, LAT, LON, SourceFetchTable.Request(parts = ForecastParts.HOURLY), NOW)

        assertEquals(listOf("SILURIAN parts=DAILY", "NWS parts=HOURLY"), asked)
    }

    @Test
    fun `Tomorrow io takes its five-minute history only with the daily part, and a failed history keeps the forecast`() = runBlocking {
        var historyFails = false
        val host = FakeHost(
            SourceFetchTable.Calls(
                nws = nws,
                openMeteo = { _, _, _, _ -> RawFetch() },
                tomorrowIoForecast = { _, _, parts -> asked += "TMRW forecast parts=$parts"; RawFetch(providerCurrentTemp = 50f) },
                tomorrowIoHistory = { _, _, hours ->
                    asked += "TMRW history hours=$hours"
                    if (historyFails) error("boom")
                    RawFetch(subHourly = listOf(HourlyForecast(NOW, 61f, "Clear")), providerCurrentTemp = 61f)
                },
            ),
        )

        table(host).fetch(WeatherSource.TOMORROW_IO, LAT, LON, SourceFetchTable.Request(parts = ForecastParts.HOURLY), NOW)
        val merged = table(host).fetch(WeatherSource.TOMORROW_IO, LAT, LON, SourceFetchTable.Request(parts = ForecastParts.BOTH), NOW)
        historyFails = true
        val kept = table(host).fetch(WeatherSource.TOMORROW_IO, LAT, LON, SourceFetchTable.Request(parts = ForecastParts.BOTH), NOW)

        // The forecast and history requests run in parallel, so compare what was asked, not the order.
        assertEquals(
            listOf(
                "TMRW forecast parts=HOURLY",
                "TMRW forecast parts=BOTH", "TMRW history hours=${TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS}",
                "TMRW forecast parts=BOTH", "TMRW history hours=${TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS}",
            ).sorted(),
            asked.sorted(),
        )
        assertEquals(1, merged.subHourly.size)
        assertEquals(61f, merged.providerCurrentTemp)
        assertEquals(50f, kept.providerCurrentTemp)
        assertTrue(kept.subHourly.isEmpty())
        assertEquals(listOf(SourceFetchTable.TMRW_HISTORY_FAIL_TAG), host.logs.map { it.first })
    }

    @Test
    fun `Google reads stored hours on the quantized site and skips history for an on-demand day`() = runBlocking {
        val requests = mutableListOf<SourceFetchTable.GoogleRequest>()
        val host = FakeHost(
            SourceFetchTable.Calls(
                nws = nws,
                openMeteo = { _, _, _, _ -> RawFetch() },
                google = { _, _, r -> requests += r; RawFetch() },
                googlePagesLine = { onDemand -> "onDemand=$onDemand" },
            ),
        )

        table(host).fetch(WeatherSource.GOOGLE_WEATHER, LAT, LON, SourceFetchTable.Request(parts = ForecastParts.BOTH), NOW)
        table(host).fetch(
            WeatherSource.GOOGLE_WEATHER, LAT, LON,
            SourceFetchTable.Request(onDemand = HourlyOnDemand.Request(WeatherSource.GOOGLE_WEATHER.id, 240)),
            NOW,
        )

        assertEquals(listOf(37.417, -122.089, NOW - HOUR, NOW + (GoogleWeatherApi.FORECAST_HOURS + 2) * HOUR), host.storedReads.first())
        assertEquals(listOf(true, false), requests.map { it.includeHistory })
        assertEquals(listOf(host.stored, host.stored), requests.map { it.storedHours })
        assertEquals(240, requests.last().hoursAhead)
        assertEquals(listOf("onDemand=false", "onDemand=true"), host.logs.filter { it.first == GoogleWeatherApi.HOURS_PAGES_TAG }.map { it.second })
    }

    @Test
    fun `a source without a client is not served`() {
        val host = FakeHost(SourceFetchTable.Calls(nws = nws, openMeteo = { _, _, _, _ -> RawFetch() }))
        assertFalse(table(host).serves(WeatherSource.GOOGLE_WEATHER))
        assertFalse(table(host).serves(WeatherSource.TOMORROW_IO))
        assertTrue(table(host).serves(WeatherSource.NWS))
        assertTrue(table(host).serves(WeatherSource.OPEN_METEO))
    }

    @Test
    fun `each follow-up rides only its owning source, the history ones only on a daily fetch`() {
        val table = WeatherSource.entries.flatMap { source ->
            ForecastParts.entries.map { parts -> "${source.id}/$parts" to SourceFetchTable.followUps(source, parts) }
        }.filter { it.second.isNotEmpty() }.toMap()

        // Desktop ran all three after every visible source's refresh (F3–F5); Android's rule is the shared one.
        assertEquals(
            mapOf(
                "OPEN_METEO/BOTH" to listOf(SourceFetchTable.FollowUp.PRIOR_DAY_CLOUD),
                "OPEN_METEO/DAILY" to listOf(SourceFetchTable.FollowUp.PRIOR_DAY_CLOUD),
                "OPEN_METEO/HOURLY" to listOf(SourceFetchTable.FollowUp.PRIOR_DAY_CLOUD),
                "WEATHER_API/BOTH" to listOf(SourceFetchTable.FollowUp.WEATHER_API_HISTORY),
                "WEATHER_API/DAILY" to listOf(SourceFetchTable.FollowUp.WEATHER_API_HISTORY),
                "NWS/BOTH" to listOf(SourceFetchTable.FollowUp.NWS_STATION_ACTUALS),
                "NWS/DAILY" to listOf(SourceFetchTable.FollowUp.NWS_STATION_ACTUALS),
            ),
            table,
        )
    }

    private val ran = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    private fun jobs(failing: String? = null) = SourceFetchTable.FollowUpJobs(
        priorDayCloud = { ran += "cloud"; if (failing == "cloud") error("boom") },
        weatherApiHistory = { ran += "wapi"; if (failing == "wapi") error("boom"); 24 },
        nwsStationActuals = { ran += "nws"; if (failing == "nws") error("boom") },
        log = { tag, message, _ -> failures += "$tag $message" },
    )

    @Test
    fun `a failed fetch runs only the follow-ups that do not need its reply`() = runBlocking {
        SourceFetchTable.runFollowUps(WeatherSource.NWS, ForecastParts.BOTH, stored = false, jobs())
        SourceFetchTable.runFollowUps(WeatherSource.OPEN_METEO, ForecastParts.BOTH, stored = false, jobs())
        SourceFetchTable.runFollowUps(WeatherSource.WEATHER_API, ForecastParts.BOTH, stored = false, jobs())

        // Station actuals are separate NWS endpoints; prior-day cloud and WeatherAPI history ride a reply.
        assertEquals(listOf("nws"), ran)
    }

    @Test
    fun `a stored fetch runs its follow-up and reports WeatherAPI's history hours`() = runBlocking {
        val hours = SourceFetchTable.runFollowUps(WeatherSource.WEATHER_API, ForecastParts.BOTH, stored = true, jobs())
        val none = SourceFetchTable.runFollowUps(WeatherSource.WEATHER_API, ForecastParts.HOURLY, stored = true, jobs())

        assertEquals(listOf("wapi"), ran)
        assertEquals(24, hours)
        assertEquals(0, none)
    }

    @Test
    fun `a follow-up that throws is logged and never fails the fetch`() = runBlocking {
        SourceFetchTable.runFollowUps(WeatherSource.NWS, ForecastParts.DAILY, stored = true, jobs(failing = "nws"))

        assertEquals(listOf("nws"), ran)
        assertEquals(1, failures.size)
        assertTrue(failures.single(), failures.single().startsWith("FOLLOW_UP_FAIL followUp=NWS_STATION_ACTUALS source=NWS stored=true"))
    }
}
