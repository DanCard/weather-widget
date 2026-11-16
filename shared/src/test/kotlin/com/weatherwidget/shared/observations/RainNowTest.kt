package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.observations.RainNow.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDateTime
import java.time.ZoneId

/** plans/261115-header-rain-now-dots.md: station, radar and Tomorrow.io inputs to the header's rain-now dots. */
@Category(ShortDuration::class)
class RainNowTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun ms(h: Int, m: Int) = LocalDateTime.of(2026, 11, 15, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(21, 0)

    private fun metar(
        station: String,
        at: Long,
        raw: String?,
        km: Float = 3.8f,
        condition: String = "",
        api: String = WeatherSource.NWS.id,
        type: StationType = StationType.OFFICIAL,
        qcFailed: Boolean = false,
    ) = ObservationReading(
        stationId = station, stationName = station, timestamp = at, temperature = 55f, condition = condition,
        locationLat = 37.417, locationLon = -122.089, distanceKm = km, stationType = type, api = api,
        rawMetar = raw, isMetar = raw != null, qcFailed = qcFailed,
    )

    private fun tomorrow(at: Long, condition: String) = ObservationReading(
        stationId = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, stationName = "Tomorrow.io", timestamp = at,
        temperature = 55f, condition = condition, locationLat = 37.417, locationLon = -122.089, distanceKm = 0f,
        // Stored exactly like this on 2026-11-15: official, 0 km — the trap the station rule must avoid.
        stationType = StationType.OFFICIAL, api = WeatherSource.TOMORROW_IO.id,
    )

    private fun radar(at: Long, r: Int, g: Int, b: Int, a: Int = 255) = RadarNowSample(37.417, -122.089, at, r, g, b, a)

    private fun level(readings: List<ObservationReading>, radar: RadarNowSample? = null, source: String = "NWS", at: Long = now) =
        RainNow.resolve(readings, radar, source, at).level.also {
            println("RainNow ${RainNow.resolve(readings, radar, source, at).describe(at)}")
        }

    private fun knuq(wx: String) = "KNUQ 160455Z AUTO 33004KT 2SM $wx OVC008 12/12 A3008 RMK AO2"

    @Test
    fun `METAR groups classify by the strongest rain`() {
        fun lvl(wx: String) = RainNow.metarLevel(MetarDecoder.decode(knuq(wx))!!.presentWeather)
        assertEquals(Level.RAINING, lvl("-RA"))
        assertEquals(Level.RAINING, lvl("-RA BR"))
        assertEquals(Level.HEAVY, lvl("+RA"))
        assertEquals(Level.HEAVY, lvl("TSRA"))
        assertEquals(Level.SPRINKLING, lvl("-DZ"))
        assertEquals(Level.RAINING, lvl("RA DZ"))
        assertEquals(Level.RAINING, lvl("UP"))
        assertEquals(Level.RAINING, lvl("-FZRA"))
        assertNull(lvl("VCSH"))
        assertNull(lvl("-SN"))
        assertNull(lvl("BR"))
        // AO2 with no weather group measured dry.
        assertNull(RainNow.metarLevel(MetarDecoder.decode("KNUQ 160055Z AUTO 13005KT 9SM BKN033 17/12 A3001 RMK AO2")!!.presentWeather))
    }

    @Test
    fun `condition words read when a row has no METAR`() {
        assertEquals(Level.RAINING, RainNow.conditionLevel("Light Rain and Fog/Mist"))
        assertEquals(Level.HEAVY, RainNow.conditionLevel("Heavy Rain"))
        assertEquals(Level.SPRINKLING, RainNow.conditionLevel(" Drizzle"))
        assertNull(RainNow.conditionLevel("Showers in Vicinity"))
        assertNull(RainNow.conditionLevel("Light Snow"))
        assertNull(RainNow.conditionLevel("Cloudy"))
    }

    @Test
    fun `the raw METAR beats the condition text`() {
        // KPAO 17:57 on 2026-11-15: condition " Rain", METAR VCSH.
        val kpao = metar("KPAO", now - 10 * 60_000, "KPAO 160157Z 31008KT 10SM VCSH BKN030 15/11 A3001", condition = " Rain")
        assertNull(level(listOf(kpao)))
    }

    @Test
    fun `a blank row is skipped so the older report still decides`() {
        val rain = metar("KNUQ", now - 40 * 60_000, knuq("-RA"))
        val blank = metar("KNUQ", now - 20 * 60_000, raw = null, condition = "")
        assertEquals(Level.RAINING, level(listOf(rain, blank)))
    }

    @Test
    fun `stale, distant, personal and QC-failed rows say nothing`() {
        assertNull(level(listOf(metar("KNUQ", now - 76 * 60_000, knuq("-RA")))))
        assertNull(level(listOf(metar("KSFO", now - 5 * 60_000, knuq("-RA"), km = 26f))))
        assertNull(level(listOf(metar("AW020", now - 5 * 60_000, null, km = 2.2f, condition = "Rain", type = StationType.PERSONAL))))
        assertNull(level(listOf(metar("KNUQ", now - 5 * 60_000, knuq("-RA"), qcFailed = true))))
    }

    @Test
    fun `the nearest station decides - KNUQ dry beats KSJC raining`() {
        val knuqDry = metar("KNUQ", now - 5 * 60_000, "KNUQ 160455Z AUTO 33004KT 10SM OVC008 12/11 A3008 RMK AO2")
        val ksjcRain = metar("KSJC", now - 5 * 60_000, "KSJC 160453Z 33004KT 3SM RA BR OVC008 12/12 A3008", km = 16f)
        assertNull(level(listOf(knuqDry, ksjcRain)))
    }

    @Test
    fun `a Tomorrow io row is not a station - it counts only while Tomorrow io is displayed`() {
        val knuqDry = metar("KNUQ", now - 5 * 60_000, "KNUQ 160455Z AUTO 33004KT 10SM OVC008 12/11 A3008 RMK AO2")
        val t = tomorrow(now - 5 * 60_000, "Rain")
        assertNull(level(listOf(t)))
        assertNull(level(listOf(knuqDry, t)))
        assertEquals(Level.RAINING, level(listOf(knuqDry, t), source = WeatherSource.TOMORROW_IO.id))
        assertEquals(Level.SPRINKLING, level(listOf(tomorrow(now - 5 * 60_000, "Drizzle")), source = WeatherSource.TOMORROW_IO.id))
        assertNull(level(listOf(tomorrow(now - 31 * 60_000, "Rain")), source = WeatherSource.TOMORROW_IO.id))
    }

    @Test
    fun `radar colour family sets the level and a stale sample says nothing`() {
        assertEquals(Level.SPRINKLING, level(emptyList(), radar(now - 60_000, 86, 149, 193)))
        assertEquals(Level.RAINING, level(emptyList(), radar(now - 60_000, 14, 214, 20)))
        assertEquals(Level.HEAVY, level(emptyList(), radar(now - 60_000, 230, 0, 0)))
        assertEquals(Level.HEAVY, level(emptyList(), radar(now - 60_000, 250, 240, 0)))
        assertNull(level(emptyList(), radar(now - 60_000, 0, 0, 0, 0)))
        // 25 min: the 20-min battery run interval plus slack, so radar-only dots do not blink between runs.
        assertEquals(Level.RAINING, level(emptyList(), radar(now - 24 * 60_000, 14, 214, 20)))
        assertNull(level(emptyList(), radar(now - 26 * 60_000, 14, 214, 20)))
    }

    @Test
    fun `the strongest input wins`() {
        val rain = metar("KNUQ", now - 5 * 60_000, knuq("-RA"))
        assertEquals(Level.HEAVY, level(listOf(rain), radar(now - 60_000, 230, 0, 0)))
        assertEquals(Level.RAINING, level(listOf(rain), radar(now - 60_000, 86, 149, 193)))
    }

    /** 2026-11-15 at the 37.417, −122.089 site: KNUQ 3.8 km, KPAO 4.4 km, radar per the plan's evidence. */
    @Test
    fun `tonight's replay`() {
        val rows = listOf(
            metar("KNUQ", ms(16, 55), "KNUQ 160055Z AUTO 13005KT 9SM BKN033 BKN040 OVC060 17/12 A3001 RMK AO2"),
            metar("KPAO", ms(16, 47), "KPAO 160047Z 13006KT 10SM VCSH BKN030 16/12 A3001", km = 4.4f),
            metar("KNUQ", ms(17, 15), "KNUQ 160115Z AUTO 14006KT 10SM BKN030 16/12 A3002 RMK AO2"),
            metar("KNUQ", ms(17, 35), "KNUQ 160135Z AUTO 32007KT 9SM BKN029 OVC034 16/12 A3002 RMK AO2"),
            metar("KNUQ", ms(17, 55), "KNUQ 160155Z AUTO 32007G17KT 7SM -RA FEW005 BKN029 OVC034 14/12 A3003 RMK AO2"),
            metar("KNUQ", ms(18, 15), "KNUQ 160215Z AUTO 31008G15KT 2 1/2SM -RA BR BKN005 OVC034 13/12 A3004 RMK AO2"),
            tomorrow(ms(17, 15), "Cloudy"),
            tomorrow(ms(17, 25), "Rain"),
            tomorrow(ms(17, 55), "Rain"),
        )
        fun at(h: Int, m: Int, radar: RadarNowSample? = null, source: String = "NWS") =
            level(rows.filter { it.timestamp <= ms(h, m) }, radar, source, ms(h, m))

        assertNull(at(16, 56))
        assertEquals(Level.SPRINKLING, at(17, 5, radar(ms(17, 4), 86, 149, 193)))
        assertNull(at(17, 26))
        assertEquals(Level.RAINING, at(17, 26, source = WeatherSource.TOMORROW_IO.id))
        assertEquals(Level.RAINING, at(17, 56))
        assertEquals(Level.RAINING, at(18, 16, radar(ms(18, 10), 14, 214, 20)))
    }
}
