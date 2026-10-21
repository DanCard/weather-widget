package com.weatherwidget.shared.nowcast

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Open-Meteo 15-minute nowcast — `plans/261021-open-meteo-15-min-nowcast.md`. */
@Category(ShortDuration::class)
class NowcastTest {
    @get:Rule val tmp = TemporaryFolder()

    private val berlin = ZoneId.of("Europe/Berlin")

    /** 2026-10-21 14:07 Berlin: inside the 14:00–14:15 slot (labelled 14:15). */
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 7, 0, 0, berlin).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 21, h, m, 0, 0, berlin).toInstant().toEpochMilli()
    private val minute = 60_000L

    /** A series of 15-min slots starting at [firstStartMs], one per value. */
    private fun series(firstStartMs: Long, vararg mm: Float, fetchedAt: Long = now) =
        NowcastSeries(mm.mapIndexed { i, v -> NowcastSlot(firstStartMs + i * SLOT_MS, v) }, fetchedAt)

    // ---- parsing ----

    @Test
    fun `full forecast reply keeps now's slot and later ones, each value filed under the quarter it ends`() {
        val reply = """
            {"timezone":"Europe/Berlin","minutely_15":{
              "time":["2026-10-21T13:45","2026-10-21T14:00","2026-10-21T14:15","2026-10-21T14:30","2026-10-21T14:45"],
              "precipitation":[9.0,8.0,0.0,0.4,null],
              "precipitation_probability":[90,80,10,40,null]}}
        """.trimIndent()
        val parsed = NowcastParser.parseSeries(reply, fetchedAtMs = now, nowMs = now)!!
        // 13:45 and 14:00 end before 14:07 → past. 14:15 is the 14:00–14:15 slot containing now.
        assertEquals(listOf(at(14, 0), at(14, 15)), parsed.slots.map { it.startMs })
        assertEquals(0.0f, parsed.slots[0].mm)
        assertEquals(0.4f, parsed.slots[1].mm)
        assertEquals(40, parsed.slots[1].chance)
        // A null amount is dropped, never read as dry.
        assertTrue(parsed.slots.none { it.startMs == at(14, 30) })
    }

    @Test
    fun `unixtime reply parses the same way and trims past three hours`() {
        val labels = (1..20).map { (at(14, 0) + it * SLOT_MS) / 1000 }
        val reply = """{"timezone":"GMT","minutely_15":{"time":$labels,"precipitation":${labels.map { 0.2 }}}}"""
        val parsed = NowcastParser.parseSeries(reply, now, now)!!
        assertEquals(at(14, 0), parsed.slots.first().startMs)
        assertTrue(parsed.slots.all { it.startMs < now + NowcastParser.KEEP_AHEAD_MS })
        assertEquals("slots starting 14:00 … 17:00 (before 14:07 + 3 h)", 13, parsed.slots.size)
    }

    @Test
    fun `no minutely block or no precipitation means no series`() {
        assertNull(NowcastParser.parseSeries("""{"hourly":{}}""", now, now))
        assertNull(NowcastParser.parseSeries("""{"minutely_15":{"time":[1]}}""", now, now))
        assertNull(NowcastParser.parseSeries("not json", now, now))
    }

    @Test
    fun `getForecast hands back the reply's future 15-minute slots as the nowcast`() = runBlocking {
        // Before 2026-10-21 parseSubHourlyHistory dropped every slot after current.time: no nowcast.
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val base = LocalDateTime.now(berlin).withSecond(0).withNano(0).let { it.withMinute(it.minute / 15 * 15) }
        val labels = (0..8).map { "\"" + base.plusMinutes(15L * it).format(fmt) + "\"" }
        val reply = """
            {"timezone":"Europe/Berlin","current":{"time":${labels[0]},"temperature_2m":50.0},
             "minutely_15":{"time":$labels,"temperature_2m":${labels.map { 50.0 }},
               "precipitation":[0,0,0,0.3,0.5,0.6,0.2,0,0]}}
        """.trimIndent()
        val api = OpenMeteoApi(HttpClient(MockEngine { respond(reply, HttpStatusCode.OK) }), Json { ignoreUnknownKeys = true })
        val nowcast = api.getForecast(52.52, 13.40, days = 1).nowcast
        assertNotNull(nowcast)
        assertTrue(nowcast!!.slots.any { it.mm == 0.5f })
        assertTrue(nowcast.slots.all { it.endMs > nowcast.fetchedAtMs })
    }

    // ---- coverage ----

    @Test
    fun `coverage probe reads every measured reply shape`() {
        // Berlin: several native models, suffixed.
        assertEquals(true, NowcastCoverage.parseProbe("""{"minutely_15":{"time":[1],"precipitation_icon_d2":[0.0],"precipitation_dmi_harmonie_arome_europe":[0.1]}}"""))
        // SF: a single model answering is not suffixed.
        assertEquals(true, NowcastCoverage.parseProbe("""{"minutely_15":{"time":[1,2],"precipitation":[0.0,0.0]}}"""))
        // Kyiv: outside every listed model.
        assertEquals(false, NowcastCoverage.parseProbe("""{"error":true,"reason":"No data is available for this location"}"""))
        // All null: no model has the site either.
        assertEquals(false, NowcastCoverage.parseProbe("""{"minutely_15":{"time":[1],"precipitation_icon_d2":[null]}}"""))
        // Anything else is unknown, not "interpolated".
        assertNull(NowcastCoverage.parseProbe("""{"error":true,"reason":"Parameter 'models' is invalid"}"""))
        assertNull(NowcastCoverage.parseProbe("<html>502</html>"))
    }

    // ---- store ----

    @Test
    fun `coverage is re-asked after 30 days, the series after 3 hours`() {
        val store = NowcastStore(tmp.root)
        store.saveCoverage(52.52, 13.40, native = true, checkedAtMs = now)
        assertEquals(true, store.loadCoverage(52.52, 13.40, now + NowcastCoverage.MAX_AGE_MS - 1))
        assertNull(store.loadCoverage(52.52, 13.40, now + NowcastCoverage.MAX_AGE_MS))
        store.saveSeries(52.52, 13.40, series(at(14, 0), 0.5f))
        assertNotNull(store.loadSeries(52.52, 13.40, now + NowcastStore.MAX_SERIES_AGE_MS - 1))
        assertNull(store.loadSeries(52.52, 13.40, now + NowcastStore.MAX_SERIES_AGE_MS))
    }

    @Test
    fun `an older series never replaces a newer one, and stored row coordinates find the active site's file`() {
        val store = NowcastStore(tmp.root)
        store.saveSeries(37.4166, -122.0888, series(at(14, 0), 1.0f, fetchedAt = now))
        store.saveSeries(37.4166, -122.0888, series(at(14, 0), 0.0f, fetchedAt = now - 10 * minute))
        // 37.417 / -122.089 is what LocationMatch.quantize stores for the same site.
        assertEquals(1.0f, store.loadSeries(37.417, -122.089, now)!!.slots.single().mm)
    }

    // ---- summary ----

    @Test
    fun `dry now then rain - starts at the first wet slot's start`() {
        val r = NowcastSummary.summarize(series(at(14, 0), 0f, 0f, 0.3f, 0.5f, 0.2f, 0f, 0f, 0f), now)
        assertEquals(NowcastSummary.Result.Starts(at(14, 30)), r)
    }

    @Test
    fun `raining now then dry - stops, and raining throughout - continues`() {
        assertEquals(
            NowcastSummary.Result.Stops(at(14, 45)),
            NowcastSummary.summarize(series(at(14, 0), 0.4f, 0.3f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f), now),
        )
        assertEquals(
            NowcastSummary.Result.Continues,
            NowcastSummary.summarize(series(at(14, 0), *FloatArray(10) { 0.3f }), now),
        )
    }

    @Test
    fun `dry throughout or under the threshold says nothing`() {
        assertNull(NowcastSummary.summarize(series(at(14, 0), *FloatArray(10) { 0f }), now))
        assertNull(NowcastSummary.summarize(series(at(14, 0), *FloatArray(10) { 0.09f }), now))
        // 0.1 mm per 15 min (0.4 mm/h) is rain.
        assertEquals(NowcastSummary.Result.Continues, NowcastSummary.summarize(series(at(14, 0), *FloatArray(10) { 0.1f }), now))
    }

    @Test
    fun `hysteresis - a lone wet slot starts nothing, a lone dry gap ends nothing`() {
        assertNull(NowcastSummary.summarize(series(at(14, 0), 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f), now))
        assertEquals(
            NowcastSummary.Result.Stops(at(15, 0)),
            NowcastSummary.summarize(series(at(14, 0), 0.5f, 0.5f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0f), now),
        )
    }

    @Test
    fun `a wet slot at the two-hour edge is judged by the slot after it`() {
        // Slot 8 (16:00, the last inside 2 h of 14:07) is wet and so is 16:15: it is a start.
        val wet = FloatArray(10) { if (it >= 8) 0.4f else 0f }
        assertEquals(NowcastSummary.Result.Starts(at(16, 0)), NowcastSummary.summarize(series(at(14, 0), *wet), now))
    }

    @Test
    fun `no slot containing now means no summary`() {
        assertNull(NowcastSummary.summarize(series(at(15, 0), 0.5f, 0.5f), now))
    }

    @Test
    fun `header and description text, 12- and 24-hour clocks`() {
        val starts = NowcastSummary.Result.Starts(at(14, 15))
        assertEquals("2:15pm–", NowcastSummary.headerText(starts, use24HourClock = false, zoneId = berlin, locale = Locale.US))
        assertEquals("14:15–", NowcastSummary.headerText(starts, use24HourClock = true, zoneId = berlin, locale = Locale.GERMANY))
        assertEquals("–3pm", NowcastSummary.headerText(NowcastSummary.Result.Stops(at(15, 0)), false, berlin, Locale.US))
        assertNull(NowcastSummary.headerText(NowcastSummary.Result.Continues, false, berlin, Locale.US))
        assertEquals("Rain from 14:15", NowcastSummary.describe(starts, true, berlin, Locale.GERMANY))
        assertEquals("40% 14:15–", NowcastDisplay.headerPrecipText(40, "14:15–"))
        assertEquals("40%", NowcastDisplay.headerPrecipText(40, null))
    }

    // ---- refresh policy ----

    @Test
    fun `each gate alone blocks the refresh, all passing fetches`() {
        val stale = series(at(13, 0), 0f, fetchedAt = now - 20 * minute)
        fun skip(displayed: Boolean = true, native: Boolean? = true, cached: NowcastSeries? = stale, pop: Int? = 60) =
            NowcastRefreshPolicy.skipReason(displayed, native, cached, pop, now)
        assertNull(skip())
        assertNull("never probed: the fetch probes first", skip(native = null))
        assertNull("nothing cached yet", skip(cached = null))
        assertEquals(NowcastRefreshPolicy.Skip.NOT_DISPLAYED, skip(displayed = false))
        assertEquals(NowcastRefreshPolicy.Skip.INTERPOLATED, skip(native = false))
        assertEquals(NowcastRefreshPolicy.Skip.FRESH, skip(cached = series(at(14, 0), 0f, fetchedAt = now - 5 * minute)))
        assertEquals(NowcastRefreshPolicy.Skip.DRY, skip(pop = 29))
        assertEquals(NowcastRefreshPolicy.Skip.DRY, skip(pop = null))
        // Dry hourly, but the series itself still shows rain ahead: keep watching it.
        assertNull(skip(pop = 0, cached = series(at(14, 0), 0f, 0.5f, fetchedAt = now - 20 * minute)))
    }

    @Test
    fun `max chance reads only the next three hours, every source`() {
        fun h(atMs: Long, pop: Int, source: String) =
            HourlyForecast(dateTime = atMs, temperature = 50f, condition = "", precipProbability = pop, source = source)
        val hourly = listOf(
            h(at(13, 0), 90, "NWS"), // ended before now
            h(at(14, 0), 20, "OPEN_METEO"), // current hour
            h(at(16, 0), 45, "NWS"),
            h(at(17, 0), 99, "NWS"), // starts before 17:07 (now + 3 h): inside
            h(at(17, 15), 100, "NWS"), // starts after it: outside
        )
        assertEquals(99, NowcastRefreshPolicy.maxPopNext3h(hourly, now))
        assertNull(NowcastRefreshPolicy.maxPopNext3h(emptyList(), now))
    }

    // ---- fetch ----

    private class Recorder {
        val urls = mutableListOf<Url>()
    }

    private fun fetcher(store: NowcastStore, recorder: Recorder, probeReply: Pair<String, HttpStatusCode>, nowcastReply: String) =
        NowcastFetcher(
            HttpClient(
                MockEngine { req ->
                    recorder.urls += req.url
                    if (req.url.parameters["models"] != null) respond(probeReply.first, probeReply.second)
                    else respond(nowcastReply, HttpStatusCode.OK)
                },
            ),
            store,
            nowMs = { now },
        )

    private val nativeProbe = """{"minutely_15":{"time":[1],"precipitation_icon_d2":[0.0]}}""" to HttpStatusCode.OK
    private val nowcastReply: String
        get() {
            val labels = (1..12).map { (at(14, 0) + it * SLOT_MS) / 1000 }
            return """{"timezone":"GMT","minutely_15":{"time":$labels,"precipitation":[0,0,0.3,0.5,0.6,0.2,0,0,0,0,0,0]}}"""
        }

    @Test
    fun `first refresh probes then fetches, the next one only fetches - same coordinate, no models on the nowcast`() = runBlocking {
        val store = NowcastStore(tmp.root)
        val rec = Recorder()
        val f = fetcher(store, rec, nativeProbe, nowcastReply)
        assertEquals(NowcastFetcher.Outcome.Fetched(slots = 12, probed = true), f.refresh(52.52, 13.405))
        assertEquals(NowcastFetcher.Outcome.Fetched(slots = 12, probed = false), f.refresh(52.52, 13.405))
        assertEquals(3, rec.urls.size)
        val nowcastUrl = rec.urls[1]
        assertNull(nowcastUrl.parameters["models"])
        assertEquals("12", nowcastUrl.parameters["forecast_minutely_15"])
        assertEquals("52.52", nowcastUrl.parameters["latitude"])
        assertEquals("13.405", nowcastUrl.parameters["longitude"])
        assertNull("no daily/hourly parts", nowcastUrl.parameters["hourly"])
        assertEquals(NowcastSummary.Result.Starts(at(14, 30)), NowcastSummary.summarize(store.loadSeries(52.52, 13.405, now)!!, now))
    }

    @Test
    fun `a site outside every native model is remembered and never fetched`() = runBlocking {
        val store = NowcastStore(tmp.root)
        val rec = Recorder()
        val kyiv = """{"error":true,"reason":"No data is available for this location"}""" to HttpStatusCode.BadRequest
        val f = fetcher(store, rec, kyiv, nowcastReply)
        assertEquals(NowcastFetcher.Outcome.Interpolated(probed = true), f.refresh(50.45, 30.52))
        assertEquals(NowcastFetcher.Outcome.Interpolated(probed = false), f.refresh(50.45, 30.52))
        assertEquals("one probe, no nowcast request", 1, rec.urls.size)
        assertEquals(false, store.loadCoverage(50.45, 30.52, now))
    }

    @Test
    fun `an unreadable probe is not remembered`() = runBlocking {
        val store = NowcastStore(tmp.root)
        val f = fetcher(store, Recorder(), "<html>502</html>" to HttpStatusCode.BadGateway, nowcastReply)
        assertEquals(NowcastFetcher.Outcome.Failed("probe_unreadable"), f.refresh(52.52, 13.40))
        assertNull(store.loadCoverage(52.52, 13.40, now))
    }

    // ---- display ----

    @Test
    fun `display needs Open-Meteo displayed and native coverage - fetch to header text end to end`() = runBlocking {
        val store = NowcastStore(tmp.root)
        fetcher(store, Recorder(), nativeProbe, nowcastReply).refresh(52.52, 13.40)
        val shown = NowcastDisplay.series(store, 52.52, 13.40, WeatherSource.OPEN_METEO.id, now)
        val text = NowcastSummary.headerText(NowcastDisplay.summary(shown, now), true, berlin, Locale.GERMANY)
        assertEquals("40% 14:30–", NowcastDisplay.headerPrecipText(40, text))
        assertNull(NowcastDisplay.series(store, 52.52, 13.40, WeatherSource.NWS.id, now))
        store.saveCoverage(52.52, 13.40, native = false, checkedAtMs = now)
        assertNull(NowcastDisplay.series(store, 52.52, 13.40, WeatherSource.OPEN_METEO.id, now))
    }

    @Test
    fun `band - one bar per wet slot from now's slot, two hours, height by intensity`() {
        val s = series(at(14, 0), 0f, 0.1f, 2.0f, *FloatArray(10) { 0.5f })
        val slots = NowcastDisplay.bandSlots(s, now)
        assertTrue(slots.all { it.endMs > now && it.startMs < now + NowcastSummary.HORIZON_MS })
        val bars = NowcastBand.bars(slots, firstHourMs = at(14, 0), hourWidthPx = 100f)
        assertEquals(25f, bars.first().leftX, 0.01f) // 14:15 → a quarter of an hour in
        assertEquals(50f, bars.first().rightX, 0.01f)
        assertEquals(NowcastBand.MIN_HEIGHT_FRACTION, bars.first().heightFraction, 0.001f) // 0.4 mm/h
        assertEquals(1f, bars[1].heightFraction, 0.001f) // 8 mm/h, capped
        assertFalse(bars.any { it.leftX < 25f }) // the dry 14:00 slot draws nothing
    }

    @Test
    fun `usage stats file the nowcast and its probe as forecast slash nowcast`() {
        assertEquals(
            ApiUsageClassifier.Key("OPEN_METEO", "forecast/nowcast"),
            ApiUsageClassifier.classify("api.open-meteo.com", "/v1/forecast", "latitude=52.5&longitude=13.4&minutely_15=precipitation&forecast_minutely_15=12"),
        )
        assertEquals(
            ApiUsageClassifier.Key("OPEN_METEO", "forecast"),
            ApiUsageClassifier.classify("api.open-meteo.com", "/v1/forecast", "latitude=52.5&longitude=13.4&minutely_15=precipitation"),
        )
    }
}
