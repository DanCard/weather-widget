package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Fixtures are real responses captured from `aviationweather.gov` on 2026-08-23, trimmed but not
 * otherwise edited. The mixed field types below are not hypothetical — they are what the live API
 * returned within a single request.
 */
@Category(ShortDuration::class)
class AviationWeatherApiParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun metars(body: String) =
        AviationWeatherApi.parseMetars(json, body).let {
            assertTrue("expected Success, got $it", it is FetchOutcome.Success)
            (it as FetchOutcome.Success).value
        }

    // ---------- the three live-observed type hazards ----------

    /**
     * `wdir` was `340` on the 17:00Z KNUQ report and `"VRB"` on the 17:15Z one — same field, same
     * station, adjacent cycles. A naive `jsonPrimitive.int` throws on the second.
     */
    @Test
    fun `wdir as int and as VRB string both parse`() {
        val body = """
            [{"icaoId":"KNUQ","obsTime":1787505300,"temp":20,"wdir":"VRB","wspd":4,
              "lat":37.4059,"lon":-122.0491,"rawOb":"METAR KNUQ 231715Z AUTO VRB04KT 10SM CLR 20/15 A2996 RMK AO2"},
             {"icaoId":"KNUQ","obsTime":1787504100,"temp":19,"wdir":340,"wspd":5,
              "lat":37.4059,"lon":-122.0491,"rawOb":"METAR KNUQ 231655Z AUTO 34005KT 10SM CLR 19/15 A2996 RMK AO2"}]
        """.trimIndent()
        val rows = metars(body)
        assertEquals(2, rows.size)
        assertEquals(20f, rows[0].temperatureCelsius!!, 1e-6f)
        assertEquals(19f, rows[1].temperatureCelsius!!, 1e-6f)
    }

    /** `visib` is the string `"10+"`, never a number. */
    @Test
    fun `visib as a plus-suffixed string does not break the row`() {
        val rows = metars(
            """[{"icaoId":"KSJC","obsTime":1787503980,"temp":20,"visib":"10+","lat":37.35,"lon":-121.92}]""",
        )
        assertEquals("KSJC", rows.single().stationId)
    }

    /** `dewp` is `15` at KNUQ (no T-group) and `14.4` at KSJC (T-group present). */
    @Test
    fun `dewp as int and as float both parse`() {
        val rows = metars(
            """[{"icaoId":"KNUQ","obsTime":1,"temp":19,"dewp":15,"lat":37.4,"lon":-122.0},
                {"icaoId":"KSJC","obsTime":2,"temp":20,"dewp":14.4,"lat":37.3,"lon":-121.9}]""",
        )
        assertEquals(15f, rows[0].dewpointCelsius!!, 1e-6f)
        assertEquals(14.4f, rows[1].dewpointCelsius!!, 1e-6f)
    }

    // ---------- timestamp identity ----------

    /**
     * `obsTime`, not `reportTime`. The observations primary key is
     * (stationId, timestamp, lat, lon); `reportTime` is pre-rounded to the hour, so two reports in
     * one hour would collide on it and one would be silently lost.
     */
    @Test
    fun `timestamp comes from obsTime, not the hour-rounded reportTime`() {
        val rows = metars(
            """[{"icaoId":"KSJC","obsTime":1787503980,"reportTime":"2026-08-23T17:00:00.000Z",
                 "temp":20,"lat":37.35,"lon":-121.92}]""",
        )
        assertEquals(1787503980L * 1000L, rows.single().observedAtMillis)
    }

    @Test
    fun `a SPECI and a METAR in the same hour stay two distinct rows`() {
        val rows = metars(
            """[{"icaoId":"KSJC","obsTime":1787503980,"reportTime":"2026-08-23T17:00:00.000Z",
                 "metarType":"METAR","temp":20,"lat":37.35,"lon":-121.92},
                {"icaoId":"KSJC","obsTime":1787505300,"reportTime":"2026-08-23T17:00:00.000Z",
                 "metarType":"SPECI","temp":22,"lat":37.35,"lon":-121.92}]""",
        )
        assertEquals(2, rows.map { it.observedAtMillis }.distinct().size)
        assertTrue(rows.single { it.isSpeci }.temperatureCelsius == 22f)
    }

    @Test
    fun `rows missing obsTime or coordinates are dropped, not defaulted`() {
        val outcome = AviationWeatherApi.parseMetars(
            json,
            """[{"icaoId":"KBAD","temp":20,"lat":37.3,"lon":-121.9},
                {"icaoId":"KALSOBAD","obsTime":1787503980,"temp":20}]""",
        )
        assertEquals(FetchOutcome.NoData, outcome)
    }

    // ---------- cloud ----------

    @Test
    fun `cloud bases convert feet to metres`() {
        val rows = metars(
            """[{"icaoId":"KSJC","obsTime":1,"temp":20,"lat":37.3,"lon":-121.9,
                 "clouds":[{"cover":"SCT","base":8000},{"cover":"BKN","base":10000}]}]""",
        )
        val layers = rows.single().cloudLayers
        assertEquals(listOf("SCT", "BKN"), layers.map { it.amount })
        assertEquals(8000 * 0.3048, layers[0].baseMeters!!, 0.01)
    }

    /** THE invariant: an empty array is "not reported", and must never become a clear layer. */
    @Test
    fun `empty clouds array yields no layers`() {
        val rows = metars(
            """[{"icaoId":"KNUQ","obsTime":1,"temp":20,"lat":37.4,"lon":-122.0,"clouds":[]}]""",
        )
        assertTrue(rows.single().cloudLayers.isEmpty())
    }

    /** A CLR cover is a positive report of clear sky and keeps its layer, with no base. */
    @Test
    fun `CLR keeps a layer with a null base`() {
        val rows = metars(
            """[{"icaoId":"KNUQ","obsTime":1,"temp":20,"lat":37.4,"lon":-122.0,
                 "clouds":[{"cover":"CLR"}]}]""",
        )
        val layer = rows.single().cloudLayers.single()
        assertEquals("CLR", layer.amount)
        assertNull(layer.baseMeters)
    }

    // ---------- outcomes ----------

    @Test
    fun `empty array is NoData, malformed body is Failed`() {
        assertEquals(FetchOutcome.NoData, AviationWeatherApi.parseMetars(json, "[]"))
        assertTrue(AviationWeatherApi.parseMetars(json, "not json") is FetchOutcome.Failed)
    }

    @Test
    fun `slp is optional`() {
        val rows = metars(
            """[{"icaoId":"KNUQ","obsTime":1,"temp":20,"lat":37.4,"lon":-122.0},
                {"icaoId":"KSJC","obsTime":2,"temp":20,"slp":1014.4,"lat":37.3,"lon":-121.9}]""",
        )
        assertNull(rows[0].seaLevelPressureHpa)
        assertEquals(1014.4f, rows[1].seaLevelPressureHpa!!, 1e-4f)
    }

    // ---------- stationinfo ----------

    @Test
    fun `stationinfo keeps siteType so non-METAR sites can be filtered`() {
        val body = """
            [{"id":"AAMC1","site":"Alameda","lat":37.772,"lon":-122.298,"elev":6,"country":"US","siteType":[]},
             {"id":"KHWD","site":"Hayward Exec","lat":37.65886,"lon":-122.12116,"elev":9,"country":"US","siteType":["METAR"]},
             {"id":"KLVK","site":"Livermore Muni","lat":37.69309,"lon":-121.81489,"elev":120,"country":"US","siteType":["METAR","TAF"]}]
        """.trimIndent()
        val outcome = AviationWeatherApi.parseStationInfo(json, body)
        val candidates = (outcome as FetchOutcome.Success).value
        assertEquals(3, candidates.size)
        assertTrue(candidates.single { it.id == "AAMC1" }.siteTypes.isEmpty())
        assertEquals(listOf("METAR", "TAF"), candidates.single { it.id == "KLVK" }.siteTypes)
        assertEquals(120.0, candidates.single { it.id == "KLVK" }.elevationMeters!!, 1e-9)
    }

    @Test
    fun `stationinfo rows without coordinates are dropped`() {
        val outcome = AviationWeatherApi.parseStationInfo(
            json,
            """[{"id":"NOWHERE","site":"x","siteType":["METAR"]}]""",
        )
        assertEquals(FetchOutcome.NoData, outcome)
    }

    /** A non-US payload must parse identically — this is the whole point of the transport. */
    @Test
    fun `international rows parse the same as domestic`() {
        val rows = metars(
            """[{"icaoId":"LFPG","obsTime":1787504400,"temp":23,"dewp":5,"lat":49.015,"lon":2.534,
                 "elev":107,"name":"Paris/De Gaulle Arpt, ID, FR","metarType":"METAR",
                 "rawOb":"METAR LFPG 231700Z 06012KT CAVOK 23/05 Q1021 NOSIG"}]""",
        )
        val row = rows.single()
        assertEquals("LFPG", row.stationId)
        assertEquals(23f, row.temperatureCelsius!!, 1e-6f)
        assertEquals(107.0, row.elevationMeters!!, 1e-9)
        assertTrue(row.rawOb!!.contains("CAVOK"))
    }
}
