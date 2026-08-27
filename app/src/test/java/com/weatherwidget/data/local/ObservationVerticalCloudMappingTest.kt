package com.weatherwidget.data.local

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ObservationVerticalCloudMappingTest {

    @Test
    fun `entity to reading preserves flat vertical cloud fields`() {
        val reading = ObservationEntity(
            stationId = "KNUQ",
            stationName = "Moffett Federal Airfield",
            timestamp = 1_000L,
            temperature = 61f,
            condition = "Cloudy",
            locationLat = 37.417,
            locationLon = -122.089,
            api = "NWS",
            cloudCover = 92,
            cloudCoverLow = 44,
            cloudCoverMid = 75,
            cloudCoverHigh = 19,
            cloudBaseLowMeters = 305,
            cloudBaseMidMeters = 3_048,
            cloudBaseHighMeters = 9_144,
            cloudEnvelopeBaseMeters = 305,
            cloudEnvelopeTopMeters = 10_000,
            cloudVerticalKind = CloudVerticalKind.CUMULATIVE_LAYERS,
        ).toReading()

        assertEquals(75, reading.cloudCoverMid)
        assertEquals(19, reading.cloudCoverHigh)
        assertEquals(305, reading.cloudBaseLowMeters)
        assertEquals(3_048, reading.cloudBaseMidMeters)
        assertEquals(9_144, reading.cloudBaseHighMeters)
        assertEquals(305, reading.cloudEnvelopeBaseMeters)
        assertEquals(10_000, reading.cloudEnvelopeTopMeters)
        assertEquals(CloudVerticalKind.CUMULATIVE_LAYERS, reading.cloudVerticalKind)
    }

    @Test
    fun `room converter uses stable codes and tolerates future values`() {
        val converters = CloudVerticalKindConverters()
        assertEquals(30, converters.toDbCode(CloudVerticalKind.TOTAL_ENVELOPE))
        assertEquals(CloudVerticalKind.TOTAL_ENVELOPE, converters.fromDbCode(30))
        assertEquals(CloudVerticalKind.OTHER, converters.fromDbCode(99))
    }
}
