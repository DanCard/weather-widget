package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarConditionFormatterTest {

    private fun layer(amount: String, baseMeters: Double? = 1000.0) = NwsApi.CloudLayer(amount, baseMeters)

    @Test
    fun `sky cover layers map to standard labels and highest coverage wins`() {
        assertEquals("Clear", MetarConditionFormatter.format(null, listOf(layer("CLR", null))))
        assertEquals("Clear", MetarConditionFormatter.format(null, listOf(layer("SKC", null))))
        assertEquals("Clear", MetarConditionFormatter.format(null, listOf(layer("NCD", null))))
        assertEquals("Clear", MetarConditionFormatter.format(null, listOf(layer("CAVOK", null))))
        assertEquals("Few Clouds", MetarConditionFormatter.format(null, listOf(layer("FEW"))))
        assertEquals("Partly Cloudy", MetarConditionFormatter.format(null, listOf(layer("SCT"))))
        assertEquals("Mostly Cloudy", MetarConditionFormatter.format(null, listOf(layer("FEW"), layer("BKN"))))
        assertEquals("Overcast", MetarConditionFormatter.format(null, listOf(layer("SCT"), layer("OVC"))))
        assertEquals("Overcast", MetarConditionFormatter.format(null, listOf(layer("VV"))))
    }

    @Test
    fun `present weather takes precedence over sky cover`() {
        val rain = MetarDecoder.decode("METAR KSJC 231653Z 00000KT 5SM -RA OVC012 20/14 A2996 RMK AO2")
        assertEquals("Light Rain", MetarConditionFormatter.format(rain, rain!!.skyLayers))

        val fog = MetarDecoder.decode("METAR KPAO 231353Z 00000KT 1/4SM FG VV002 12/12 A2996")
        assertEquals("Fog", MetarConditionFormatter.format(fog, fog!!.skyLayers))

        val multi = MetarDecoder.decode("METAR KSJC 231653Z 00000KT 3SM -RA BR BKN015 15/14 A2996")
        assertEquals("Light Rain, Mist", MetarConditionFormatter.format(multi, multi!!.skyLayers))
    }

    @Test
    fun `present weather tokens format intensity, descriptor, and vicinity`() {
        assertEquals("Heavy Thunderstorm & Rain", MetarConditionFormatter.formatWeatherToken("+TSRA"))
        assertEquals("Rain Showers", MetarConditionFormatter.formatWeatherToken("SHRA"))
        assertEquals("Showers Nearby", MetarConditionFormatter.formatWeatherToken("VCSH"))
        assertEquals("Freezing Rain", MetarConditionFormatter.formatWeatherToken("FZRA"))
        assertEquals("Haze", MetarConditionFormatter.formatWeatherToken("HZ"))
        assertEquals("Snow", MetarConditionFormatter.formatWeatherToken("SN"))
    }

    @Test
    fun `unreported weather and sky stays blank rather than inventing Clear`() {
        val silent = MetarDecoder.decode("METAR KSJC 231653Z AUTO 00000KT 10SM 20/14 A2996 RMK AO2")
        assertEquals("", MetarConditionFormatter.format(silent, emptyList()))
        assertEquals("", MetarConditionFormatter.format(null, emptyList()))
    }
}
