package com.weatherwidget.data.remote

import com.weatherwidget.shared.observations.MetarSkyCover
import com.weatherwidget.test.category.ShortDuration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Fixture-driven parse of the METAR `cloudLayers` array, off captured `api.weather.gov` report
 * shapes (probed 2026-08-20): the KSJC CLR row whose base is the ceilometer limit, the KNUQ CLR
 * row with a null base, a multi-layer BKN report, and the empty-layers partial report that means
 * "sky condition not reported", never "clear".
 */
@Category(ShortDuration::class)
class NwsApiCloudLayersParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseObservation(propertiesJson: String): NwsApi.Observation? {
        val props = json.parseToJsonElement(propertiesJson).jsonObject
        return NwsApi.parseObservationProperties(props, defaultStationName = "TEST")
    }

    @Test
    fun `KSJC CLR row carries its base as the detection ceiling`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T21:53:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 22.2, "qualityControl": "V"},
              "textDescription": "Clear",
              "cloudLayers": [
                {"base": {"unitCode": "wmoUnit:m", "value": 3810}, "amount": "CLR"}
              ]
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse")

        assertEquals(listOf(NwsApi.CloudLayer(amount = "CLR", baseMeters = 3810.0)), obs.cloudLayers)
        // The 3810 m base is the sensor's blind spot, not a cloud: CLR reads clear at 0.
        assertEquals(0, MetarSkyCover.lowPercent(obs.cloudLayers))
        assertEquals(0, MetarSkyCover.totalPercent(obs.cloudLayers))
    }

    @Test
    fun `KNUQ CLR row with null base parses a null base`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T23:35:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 17.8, "qualityControl": "V"},
              "textDescription": "Clear",
              "cloudLayers": [
                {"base": {"unitCode": "wmoUnit:m", "value": null}, "amount": "CLR"}
              ]
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse")

        assertEquals(listOf(NwsApi.CloudLayer(amount = "CLR", baseMeters = null)), obs.cloudLayers)
        assertEquals(0, MetarSkyCover.lowPercent(obs.cloudLayers))
    }

    @Test
    fun `multi-layer report parses every layer with its metre base`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T21:47:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 21.1, "qualityControl": "Z"},
              "textDescription": "Mostly Cloudy",
              "cloudLayers": [
                {"base": {"unitCode": "wmoUnit:m", "value": 304.8}, "amount": "FEW"},
                {"base": {"unitCode": "wmoUnit:m", "value": 609.6}, "amount": "SCT"},
                {"base": {"unitCode": "wmoUnit:m", "value": 1219.2}, "amount": "BKN"}
              ]
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse")

        assertEquals(3, obs.cloudLayers.size)
        assertEquals(NwsApi.CloudLayer(amount = "BKN", baseMeters = 1219.2), obs.cloudLayers.last())
        // FEW010 SCT020 BKN040: cumulative maximum -> BKN overall.
        assertEquals(75, MetarSkyCover.totalPercent(obs.cloudLayers))
        assertEquals(75, MetarSkyCover.lowPercent(obs.cloudLayers))
    }

    @Test
    fun `partial report with empty layers means not reported`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T22:05:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 19.4, "qualityControl": "Z"},
              "textDescription": "",
              "cloudLayers": []
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse")

        assertTrue(obs.cloudLayers.isEmpty())
        assertNull(MetarSkyCover.lowPercent(obs.cloudLayers))
        assertNull(MetarSkyCover.totalPercent(obs.cloudLayers))
    }

    @Test
    fun `absent cloudLayers key parses as empty, not reported`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T22:05:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 19.4, "qualityControl": "Z"},
              "textDescription": ""
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse")

        assertTrue(obs.cloudLayers.isEmpty())
        assertNull(MetarSkyCover.lowPercent(obs.cloudLayers))
    }

    @Test
    fun `JSON-null cloudLayers degrades to not reported and keeps the temperature`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T22:05:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 19.4, "qualityControl": "Z"},
              "textDescription": "",
              "cloudLayers": null
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse — a null cloudLayers must not drop it")

        assertTrue(obs.cloudLayers.isEmpty())
        assertEquals(19.4f, obs.temperatureCelsius)
    }

    @Test
    fun `malformed layer entries are skipped without dropping the observation`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T22:05:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 19.4, "qualityControl": "Z"},
              "textDescription": "",
              "cloudLayers": [
                "not-an-object",
                {"amount": null},
                {"base": null, "amount": "SCT"}
              ]
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse — malformed layers must not drop it")

        assertEquals(listOf(NwsApi.CloudLayer(amount = "SCT", baseMeters = null)), obs.cloudLayers)
    }

    @Test
    fun `foot unit converts to metres`() {
        val obs = parseObservation(
            """
            {
              "timestamp": "2026-08-20T21:53:00+00:00",
              "temperature": {"unitCode": "wmoUnit:degC", "value": 20.0, "qualityControl": "V"},
              "textDescription": "Partly Cloudy",
              "cloudLayers": [
                {"base": {"unitCode": "wmoUnit:ft", "value": 2000}, "amount": "SCT"}
              ]
            }
            """.trimIndent(),
        ) ?: throw AssertionError("observation did not parse")

        val base = obs.cloudLayers.single().baseMeters ?: throw AssertionError("base missing")
        assertEquals(609.6, base, 0.01)
        // 609.6 m is below the 2000 m low ceiling, so the layer joins the low read.
        assertEquals(44, MetarSkyCover.lowPercent(obs.cloudLayers))
    }
}
