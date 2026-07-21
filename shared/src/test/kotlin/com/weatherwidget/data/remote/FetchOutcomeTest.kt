package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the tri-state observation-fetch contract: NoData (source definitively empty) and Failed
 * (nothing learned) must stay distinguishable end to end, because they drive opposite fetchedAt
 * handling — a dead network must never masquerade as a silent station, and vice versa.
 */
@Category(ShortDuration::class)
class FetchOutcomeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun feature(timestamp: String, tempValue: String) = """
        {"properties": {"timestamp": "$timestamp", "temperature": {"value": $tempValue, "unitCode": "wmoUnit:degC"}}}
    """.trimIndent()

    // --- selectValidObservation outcome matrix ---

    @Test
    fun `valid observation wins even behind null-temperature reports`() {
        // KNUQ 2026-07-13 shape: newest reports carry null temperatures (mis-routed METARs),
        // a valid one sits further back in the window.
        val response = """{"features": [
            ${feature("2026-07-13T13:55:00+00:00", "null")},
            ${feature("2026-07-13T13:35:00+00:00", "null")},
            ${feature("2026-07-13T03:15:00+00:00", "22")}
        ]}"""

        val outcome = NwsApi.selectValidObservation(json, response, "KNUQ")

        assertTrue(outcome is FetchOutcome.Success)
        assertEquals(22f, (outcome as FetchOutcome.Success).value.temperatureCelsius)
        assertEquals("2026-07-13T03:15:00+00:00", outcome.value.timestamp)
    }

    @Test
    fun `all null-temperature reports is NoData not Failed`() {
        val response = """{"features": [
            ${feature("2026-07-13T13:55:00+00:00", "null")},
            ${feature("2026-07-13T13:35:00+00:00", "null")}
        ]}"""
        assertEquals(FetchOutcome.NoData, NwsApi.selectValidObservation(json, response, "KNUQ"))
    }

    @Test
    fun `empty features is NoData`() {
        assertEquals(FetchOutcome.NoData, NwsApi.selectValidObservation(json, """{"features": []}""", "KNUQ"))
    }

    @Test
    fun `malformed response is Failed not NoData`() {
        assertTrue(NwsApi.selectValidObservation(json, """{"unexpected": true}""", "KNUQ") is FetchOutcome.Failed)
        assertTrue(NwsApi.selectValidObservation(json, "not json at all", "KNUQ") is FetchOutcome.Failed)
    }

    // --- Synoptic timeseries outcome mapping ---

    @Test
    fun `synoptic api-level rejection is Failed`() {
        val response = """{"SUMMARY": {"RESPONSE_CODE": 2, "RESPONSE_MESSAGE": "Invalid token"}}"""
        val outcome = SynopticApi.parseSynopticTimeseries(json, response, "KNUQ", "Moffett")
        assertTrue(outcome is FetchOutcome.Failed)
        assertTrue((outcome as FetchOutcome.Failed).reason.contains("Invalid token"))
    }

    @Test
    fun `synoptic success without observations is NoData`() {
        // Station exists but has nothing in the window — Synoptic omits the structures.
        val noStation = """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": []}"""
        assertEquals(FetchOutcome.NoData, SynopticApi.parseSynopticTimeseries(json, noStation, "KNUQ", "Moffett"))

        val noTemps = """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"NAME": "Moffett",
            "OBSERVATIONS": {"date_time": ["2026-07-13T14:00:00Z"], "air_temp_set_1": [null]}}]}"""
        assertEquals(FetchOutcome.NoData, SynopticApi.parseSynopticTimeseries(json, noTemps, "KNUQ", "Moffett"))
    }

    @Test
    fun `synoptic temperature-bearing series is Success and never empty`() {
        val response = """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"NAME": "Moffett",
            "OBSERVATIONS": {"date_time": ["2026-07-13T14:00:00Z"], "air_temp_set_1": [21.5]}}]}"""
        val outcome = SynopticApi.parseSynopticTimeseries(json, response, "KNUQ", "Moffett")
        assertTrue(outcome is FetchOutcome.Success)
        assertEquals(21.5f, (outcome as FetchOutcome.Success).value.single().temperatureCelsius)
    }

    @Test
    fun `synoptic QC-flagged reading is marked while clean sibling stays usable`() {
        // KPAO 2026-07-13 shape: a 10°C ob between 22–23°C neighbors, flagged by Synoptic's
        // spatial value check (105). Both readings are kept — the flagged one marked so the
        // stations UI can show the failure — but only the clean one is blend-usable.
        val response = """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"NAME": "Palo Alto",
            "OBSERVATIONS": {"date_time": ["2026-07-14T02:47:00Z", "2026-07-14T03:47:00Z"],
                "air_temp_set_1": [22.0, 10.0]},
            "QC_FLAGGED": true,
            "QC": {"air_temp_set_1": [null, [105]]}}]}"""
        val outcome = SynopticApi.parseSynopticTimeseries(json, response, "KPAO", "Palo Alto")
        assertTrue(outcome is FetchOutcome.Success)
        val readings = (outcome as FetchOutcome.Success).value
        assertEquals(2, readings.size)
        assertFalse(readings[0].qcFailed)
        assertEquals(22.0f, readings[0].temperatureCelsius)
        assertTrue(readings[1].qcFailed)
    }

    @Test
    fun `synoptic all readings QC-flagged is Success with every reading marked`() {
        // Data WAS learned (the station reported, badly) — Success, not NoData, so fetchedAt
        // semantics treat it as a completed fetch. Consumers must find no usable latest.
        val response = """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"NAME": "Palo Alto",
            "OBSERVATIONS": {"date_time": ["2026-07-14T03:47:00Z"], "air_temp_set_1": [10.0]},
            "QC_FLAGGED": true,
            "QC": {"air_temp_set_1": [[105]]}}]}"""
        val outcome = SynopticApi.parseSynopticTimeseries(json, response, "KPAO", "Palo Alto")
        assertTrue(outcome is FetchOutcome.Success)
        assertTrue((outcome as FetchOutcome.Success).value.all { it.qcFailed })
    }

    @Test
    fun `synoptic response without QC block marks nothing`() {
        // qc_flags responses omit the QC block entirely when nothing was flagged.
        val response = """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"NAME": "Palo Alto",
            "OBSERVATIONS": {"date_time": ["2026-07-14T02:47:00Z"], "air_temp_set_1": [22.0]},
            "QC_FLAGGED": false}]}"""
        val outcome = SynopticApi.parseSynopticTimeseries(json, response, "KPAO", "Palo Alto")
        assertTrue(outcome is FetchOutcome.Success)
        assertFalse((outcome as FetchOutcome.Success).value.single().qcFailed)
    }

    // --- fetchedAt touch decision table ---

    @Test
    fun `touch decision requires a definitive NoData from at least one upstream`() {
        val noData = FetchOutcome.NoData
        val failed = FetchOutcome.Failed("ConnectException: down")

        assertTrue(shouldTouchObservationFetchedAt(primary = noData, fallback = noData))
        assertTrue(shouldTouchObservationFetchedAt(primary = noData, fallback = failed))
        assertTrue(shouldTouchObservationFetchedAt(primary = noData, fallback = null)) // fallback not tried
        assertTrue(shouldTouchObservationFetchedAt(primary = failed, fallback = noData))

        // Everything failed → nothing learned → fetchedAt must stay frozen.
        assertFalse(shouldTouchObservationFetchedAt(primary = failed, fallback = failed))
        assertFalse(shouldTouchObservationFetchedAt(primary = failed, fallback = null))
    }
}
