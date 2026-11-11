package com.weatherwidget.shared.fog

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.observations.MetarDecoder
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Observed fog from real METARs (plans/261111-daily-bar-morning-fog.md). The fixtures are the reports
 * stored on 2026-11-09 and 2026-11-11 for KNUQ (Moffett, 2–4 km), KPAO and KSFO.
 */
@Category(ShortDuration::class)
class ObservedMorningFogTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val nov11 = LocalDate.of(2026, 11, 11)
    private val nov9 = LocalDate.of(2026, 11, 9)

    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun metar(
        station: String,
        day: LocalDate,
        h: Int,
        m: Int,
        raw: String,
        km: Float = 3.8f,
    ): ObservationReading {
        val report = MetarDecoder.decode(raw)!!
        val tempF = report.bestTemperatureCelsius!! * 9f / 5f + 32f
        return ObservationReading(
            stationId = station, stationName = station, timestamp = at(day, h, m), temperature = tempF,
            condition = "", locationLat = 37.4, locationLon = -122.0, distanceKm = km, api = "NWS",
            isMetar = true, rawMetar = raw,
        )
    }

    // --- decoder ---

    @Test
    fun `decoder reads visibility, present weather and wind`() {
        val knuq = MetarDecoder.decode("METAR KNUQ 111315Z AUTO 00000KT 3SM BR SCT002 BKN007 11/10 A3008 RMK AO2")!!
        assertEquals(3f, knuq.visibilityStatuteMiles!!, 0f)
        assertEquals(listOf("BR"), knuq.presentWeather)
        assertEquals(0, knuq.windSpeedKt)

        val ksfo = MetarDecoder.decode("SPECI KSFO 111410Z 21005KT 1/4SM R28R/2000V2400FT FG VV002 13/12 A3008 RMK AO2 T01280122 \$")!!
        assertEquals(0.25f, ksfo.visibilityStatuteMiles!!, 0f)
        assertEquals(listOf("FG"), ksfo.presentWeather)
        assertEquals(5, ksfo.windSpeedKt)
        assertEquals(12.8f, ksfo.bestTemperatureCelsius!!, 0.01f)
        assertEquals(12.2f, ksfo.bestDewpointCelsius!!, 0.01f)

        val below = MetarDecoder.decode("METAR KNUQ 091355Z AUTO 00000KT M1/4SM FG OVC002 12/11 A3009 RMK AO2")!!
        assertEquals(0.125f, below.visibilityStatuteMiles!!, 0f)

        val mixed = MetarDecoder.decode("SPECI KSFO 111348Z 18005KT 1 1/2SM R28R/3500VP6000FT BR OVC002 13/13 A3008 RMK AO2 VIS NW 3/4")!!
        assertEquals(1.5f, mixed.visibilityStatuteMiles!!, 0f)

        val gust = MetarDecoder.decode("METAR KSJC 111853Z VRB04G15KT 10SM CLR 20/09 A3007")!!
        assertEquals(4, gust.windSpeedKt)
        assertEquals(10f, gust.visibilityStatuteMiles!!, 0f)
        assertTrue(gust.presentWeather.isEmpty())
    }

    // --- the two station rules ---

    @Test
    fun `station flag counts at any visibility`() {
        val mist6 = MetarDecoder.decode("METAR KNUQ 111335Z AUTO 00000KT 6SM BR BKN002 BKN007 11/10 A3008 RMK AO2")!!
        assertEquals(FogLevel.LIGHT, ObservedMorningFog.metarFog(mist6)!!.first)
        val dense = MetarDecoder.decode("METAR KNUQ 091355Z AUTO 00000KT M1/4SM FG OVC002 12/11 A3009 RMK AO2")!!
        assertEquals(FogLevel.DENSE, ObservedMorningFog.metarFog(dense)!!.first)
        val fog = MetarDecoder.decode("SPECI KSFO 111410Z 21005KT 1/4SM FG VV002 13/12 A3008 RMK AO2 T01280122")!!
        assertEquals(FogLevel.FOG, ObservedMorningFog.metarFog(fog)!!.first)
        // KPAO 06:47: fog visibility, sky obscured, but no FG written.
        val kpao = MetarDecoder.decode("KPAO 111447Z 00000KT 1/16SM VV001 12/12 A3019")!!
        assertEquals(FogLevel.DENSE, ObservedMorningFog.metarFog(kpao)!!.first)
        // Heavy rain cutting visibility is not fog.
        val rain = MetarDecoder.decode("METAR KSFO 141656Z 18020KT 1/2SM +RA OVC008 13/12 A2990")!!
        assertNull(ObservedMorningFog.metarFog(rain))
        // KNUQ 06:55: mist at 3/4 SM with overcast at 200 ft is fog.
        val knuq = MetarDecoder.decode("METAR KNUQ 111455Z AUTO 00000KT 3/4SM BR OVC002 10/10 A3009 RMK AO2")!!
        assertEquals(FogLevel.FOG, ObservedMorningFog.metarFog(knuq)!!.first)
    }

    @Test
    fun `saturation counts only with calm wind and a sign of condensation`() {
        // 06:15, the user saw heavy fog: 10/10, calm, 7 SM with cloud at 200 ft, no BR.
        val condensing = MetarDecoder.decode("METAR KNUQ 111415Z AUTO 00000KT 7SM FEW002 FEW006 10/10 A3008 RMK AO2")!!
        assertEquals(FogLevel.HINT, ObservedMorningFog.metarFog(condensing)!!.first)
        // 00:15, a clear night: saturated and calm, but 10 SM and no cloud (side-agent note, user 2026-11-11).
        val clearNight = MetarDecoder.decode("METAR KNUQ 110815Z AUTO 00000KT 10SM CLR 13/13 A3013 RMK AO2")!!
        assertNull(ObservedMorningFog.metarFog(clearNight))
        // 01:15: saturated, 10 SM, cloud at 3,800 ft — still no condensation at the ground.
        val highCloud = MetarDecoder.decode("METAR KNUQ 110915Z AUTO 00000KT 10SM FEW038 12/12 A3013 RMK AO2")!!
        assertNull(ObservedMorningFog.metarFog(highCloud))
        // Precise tenths that differ: KSJC 10.6/8.9 is not saturated though both round near.
        val sjc = MetarDecoder.decode("SPECI KSJC 111431Z 00000KT 7SM FEW003 11/09 A3008 RMK AO2 T01060089")!!
        assertNull(ObservedMorningFog.metarFog(sjc))
        // Wind mixes it away.
        val windy = MetarDecoder.decode("METAR KNUQ 111415Z AUTO 31012KT 7SM FEW002 10/10 A3008 RMK AO2")!!
        assertNull(ObservedMorningFog.metarFog(windy))
    }

    // --- whole mornings ---

    @Test
    fun `nov 9 KNUQ dense fog ends between the last mist and the first clear report`() {
        val day = nov9
        val readings = listOf(
            metar("KNUQ", day, 4, 55, "METAR KNUQ 091255Z AUTO 00000KT 10SM CLR 11/09 A3009 RMK AO2"),
            metar("KNUQ", day, 5, 35, "METAR KNUQ 091335Z AUTO 00000KT 3SM BR OVC004 12/11 A3009 RMK AO2"),
            metar("KNUQ", day, 5, 55, "METAR KNUQ 091355Z AUTO 00000KT M1/4SM FG OVC002 12/11 A3009 RMK AO2"),
            metar("KNUQ", day, 8, 35, "METAR KNUQ 091635Z AUTO 00000KT 1/2SM BR OVC003 13/13 A3013 RMK AO2"),
            metar("KNUQ", day, 9, 15, "METAR KNUQ 091715Z AUTO 00000KT 1/4SM FG OVC003 13/13 A3013 RMK AO2"),
            metar("KNUQ", day, 9, 35, "METAR KNUQ 091735Z AUTO 00000KT 2 1/2SM BR OVC003 13/13 A3013 RMK AO2"),
            metar("KNUQ", day, 9, 55, "METAR KNUQ 091755Z AUTO 00000KT 5SM BR BKN004 14/14 A3013 RMK AO2"),
            // 20 minutes after the last mist: saturated and calm with cloud at 400 ft — a hint, which must
            // not prolong a morning of real fog (user: "Fog only lasted till around 10 am").
            metar("KNUQ", day, 10, 15, "METAR KNUQ 091815Z AUTO 00000KT 8SM FEW004 15/15 A3012 RMK AO2"),
            metar("KNUQ", day, 10, 35, "METAR KNUQ 091835Z AUTO 00000KT 10SM CLR 17/13 A3012 RMK AO2"),
            // Farther KPAO still foggy after 10: KNUQ reported that hour and is nearer, so it stands.
            metar("KPAO", day, 10, 18, "KPAO 091818Z 00000KT 5SM FG SKC 14/14 A3013", km = 6f),
        )
        val fog = ObservedMorningFog.forDate(readings, day, zone)!!
        println("nov9 fog=$fog")
        assertEquals(FogLevel.DENSE, fog.level)
        assertEquals(0.125f, fog.visibilityMi!!, 0f)
        // The fog proper ended at 09:15 (¼ SM FG, 13 °C); 09:35's 2½ SM mist (13 °C) is the first report
        // below it, so the top is 55.4 °F. Trailing mist set it to 58.1 °F (midway 09:55 / 10:15) — half
        // the bar for a few hours of fog (plans/261111-fog-height-dense-top-bottom-weighted.md). Before
        // that, the hint at 10:15 carried it to 10:35's 62.6 °F.
        assertEquals(55.4f, fog.topTempF!!, 0.01f)
    }

    @Test
    fun `mist-only morning is measured by its mist, and mist before the fog does not matter`() {
        val day = nov9
        val mistOnly = listOf(
            metar("KNUQ", day, 5, 35, "METAR KNUQ 091335Z AUTO 00000KT 3SM BR OVC004 12/11 A3009 RMK AO2"),
            metar("KNUQ", day, 9, 35, "METAR KNUQ 091735Z AUTO 00000KT 2 1/2SM BR OVC003 13/13 A3013 RMK AO2"),
            metar("KNUQ", day, 9, 55, "METAR KNUQ 091755Z AUTO 00000KT 5SM BR BKN004 14/14 A3013 RMK AO2"),
            metar("KNUQ", day, 10, 35, "METAR KNUQ 091835Z AUTO 00000KT 10SM CLR 17/13 A3012 RMK AO2"),
        )
        val mist = ObservedMorningFog.forDate(mistOnly, day, zone)!!
        println("mist-only fog=$mist")
        assertEquals(FogLevel.LIGHT, mist.level)
        // Midway 09:55 mist (14 °C = 57.2 °F) and 10:35 clear (17 °C = 62.6 °F): mist is all there was.
        assertEquals(59.9f, mist.topTempF!!, 0.01f)

        // Mist then fog: the fog's end sets the top, and the mist still sets nothing above it.
        val mistThenFog = mistOnly.take(1) + listOf(
            metar("KNUQ", day, 6, 55, "METAR KNUQ 091455Z AUTO 00000KT M1/4SM FG OVC002 12/11 A3009 RMK AO2"),
        ) + mistOnly.drop(1)
        val fog = ObservedMorningFog.forDate(mistThenFog, day, zone)!!
        println("mist-then-fog fog=$fog")
        assertEquals(FogLevel.DENSE, fog.level)
        assertEquals(0.125f, fog.visibilityMi!!, 0f)
        // Midway 06:55 FG (12 °C) and 09:35 mist (13 °C) = 54.5 °F.
        assertEquals(54.5f, fog.topTempF!!, 0.01f)
    }

    @Test
    fun `nov 11 KNUQ mist plus saturation, nothing on the clear night hours`() {
        val day = nov11
        val readings = listOf(
            metar("KNUQ", day, 0, 15, "METAR KNUQ 110815Z AUTO 00000KT 10SM CLR 13/13 A3013 RMK AO2"),
            metar("KNUQ", day, 1, 15, "METAR KNUQ 110915Z AUTO 00000KT 10SM FEW038 12/12 A3013 RMK AO2"),
            metar("KNUQ", day, 4, 55, "METAR KNUQ 111255Z AUTO 26003KT 9SM CLR 11/10 A3008 RMK AO2"),
            metar("KNUQ", day, 5, 15, "METAR KNUQ 111315Z AUTO 00000KT 3SM BR SCT002 BKN007 11/10 A3008 RMK AO2"),
            metar("KNUQ", day, 5, 35, "METAR KNUQ 111335Z AUTO 00000KT 6SM BR BKN002 BKN007 11/10 A3008 RMK AO2"),
            metar("KNUQ", day, 6, 15, "METAR KNUQ 111415Z AUTO 00000KT 7SM FEW002 FEW006 10/10 A3008 RMK AO2"),
            metar("KNUQ", day, 6, 35, "METAR KNUQ 111435Z AUTO 00000KT 6SM BR SCT002 10/10 A3008 RMK AO2"),
        )
        val fog = ObservedMorningFog.forDate(readings, day, zone)!!
        println("nov11 fog=$fog")
        assertEquals(FogLevel.LIGHT, fog.level)
        assertEquals(3f, fog.visibilityMi!!, 0f)
        // Still foggy at the latest report: the top is its temperature (10 °C).
        assertEquals(50f, fog.topTempF!!, 0.01f)

        // The clear night alone is not fog.
        assertEquals(MorningFog.NONE, ObservedMorningFog.forDate(readings.take(2), day, zone))
    }

    @Test
    fun `saturation alone is a hint, and rows that cannot speak to fog are skipped`() {
        val day = nov11
        val hint = ObservedMorningFog.forDate(
            listOf(
                metar("KNUQ", day, 6, 15, "METAR KNUQ 111415Z AUTO 00000KT 7SM FEW002 FEW006 10/10 A3008 RMK AO2"),
                metar("KNUQ", day, 7, 15, "METAR KNUQ 111515Z AUTO 00000KT 10SM CLR 12/10 A3008 RMK AO2"),
            ),
            day, zone,
        )!!
        assertEquals(FogLevel.HINT, hint.level)
        assertEquals(0.125f, FogOpacity.alpha(hint), 0.001f) // hint at 7 SM: between 5 % (10 SM) and 15 % (6 SM)

        // A conditionless personal station 1 km away does not displace KNUQ, and alone says nothing.
        val cwop = ObservationReading(
            stationId = "AW020", stationName = "AW020", timestamp = at(day, 5, 20), temperature = 51f,
            condition = "", locationLat = 37.4, locationLon = -122.0, distanceKm = 1f, api = "NWS",
        )
        assertNull(ObservedMorningFog.forDate(listOf(cwop), day, zone))
        val withKnuq = ObservedMorningFog.forDate(
            listOf(cwop, metar("KNUQ", day, 5, 15, "METAR KNUQ 111315Z AUTO 00000KT 3SM BR SCT002 BKN007 11/10 A3008 RMK AO2")),
            day, zone,
        )
        assertNotNull(withKnuq)
        assertTrue(withKnuq!!.isFoggy)
    }

    @Test
    fun `model analysis rows read their words`() {
        val day = nov11
        fun row(h: Int, condition: String) = ObservationReading(
            stationId = "TOMORROW_IO_5M_HISTORY", stationName = "", timestamp = at(day, h, 0), temperature = 53f + h * 0.2f,
            condition = condition, locationLat = 37.4, locationLon = -122.0, api = "TOMORROW_IO",
        )
        val fog = ObservedMorningFog.forDate(listOf(row(2, "Fog"), row(5, "Fog"), row(6, "Partly Cloudy")), day, zone)!!
        assertEquals(FogLevel.FOG, fog.level)
        assertEquals(0.5f, fog.visibilityMi!!, 0f) // stand-in for the word
        assertEquals(((53f + 5 * 0.2f) + (53f + 6 * 0.2f)) / 2f, fog.topTempF!!, 0.01f)
        assertFalse(ObservedMorningFog.forDate(listOf(row(3, "Haze")), day, zone)!!.isFoggy)
    }
}
