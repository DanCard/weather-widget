package com.weatherwidget.desktop

import com.weatherwidget.data.remote.NwsApi
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopStationSelectionTest {
    @Test
    fun `orderStations prefers official stations and preserves list order within type`() {
        val stations = listOf(
            station("AW020", NwsApi.StationType.PERSONAL),
            station("KNUQ", NwsApi.StationType.OFFICIAL),
            station("KPAO", NwsApi.StationType.OFFICIAL),
            station("FW1234", NwsApi.StationType.PERSONAL),
        )

        assertEquals(listOf("KNUQ", "KPAO", "AW020", "FW1234"), orderStations(stations).map { it.id })
    }

    private fun station(id: String, type: NwsApi.StationType) =
        NwsApi.StationInfo(id = id, name = id, lat = 37.0, lon = -122.0, type = type)
}
