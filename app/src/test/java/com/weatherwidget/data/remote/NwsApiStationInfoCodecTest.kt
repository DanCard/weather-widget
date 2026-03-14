package com.weatherwidget.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NwsApiStationInfoCodecTest {
    @Test
    fun `decodeStationInfo restores type from new tab-delimited cache format`() {
        val decoded = NwsApi.decodeStationInfo("KNUQ\tMountain View, Moffett Field\t37.40583\t-122.04806\tOFFICIAL")

        assertNotNull(decoded)
        assertEquals("KNUQ", decoded!!.id)
        assertEquals("Mountain View, Moffett Field", decoded.name)
        assertEquals(NwsApi.StationType.OFFICIAL, decoded.type)
    }

    @Test
    fun `decodeStationInfo reclassifies old comma cache format with commas in station name`() {
        val decoded = NwsApi.decodeStationInfo("KSJC,San Jose, San Jose International Airport,37.35917,-121.92417")

        assertNotNull(decoded)
        assertEquals("KSJC", decoded!!.id)
        assertEquals("San Jose, San Jose International Airport", decoded.name)
        assertEquals(NwsApi.StationType.OFFICIAL, decoded.type)
    }

    @Test
    fun `decodeStationInfo reclassifies old tab cache entries without explicit type`() {
        val decoded = NwsApi.decodeStationInfo("AW020\tAE6EO MOUNTAIN VIEW\t37.4015\t-122.10517")

        assertNotNull(decoded)
        assertEquals("AW020", decoded!!.id)
        assertEquals(NwsApi.StationType.PERSONAL, decoded.type)
    }
}
