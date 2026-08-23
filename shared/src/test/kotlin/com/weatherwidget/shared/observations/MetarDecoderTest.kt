package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarDecoderTest {

    @Test
    fun `decode returns null for blank or missing sentinel`() {
        assertNull(MetarDecoder.decode(null))
        assertNull(MetarDecoder.decode(""))
        assertNull(MetarDecoder.decode("   "))
        assertNull(MetarDecoder.decode("M"))
    }

    @Test
    fun `decode full standard KSJC METAR with remarks T-group and extremes`() {
        val raw = "METAR KSJC 231653Z 00000KT 10SM SCT080 BKN100 20/14 A2996 RMK AO2 SLP144 T02000144 10244 20133 402560122 P0002"
        val report = MetarDecoder.decode(raw)

        assertNotNull(report)
        assertEquals(MetarDecoder.ReportType.METAR, report!!.reportType)
        assertEquals("KSJC", report.stationId)
        assertFalse(report.isAuto)
        assertEquals(20f, report.bodyTemperatureCelsius)
        assertEquals(14f, report.bodyDewpointCelsius)
        assertEquals(29.96f, report.altimeterInHg!!, 0.001f)
        assertEquals(2, report.skyLayers.size)

        val rmk = report.remarks
        assertNotNull(rmk)
        assertTrue(rmk!!.isAutoStation)
        assertTrue(rmk.hasPrecipDiscriminator)
        assertFalse(rmk.maintenanceNeeded)
        assertEquals(1014.4f, rmk.seaLevelPressureHpa!!, 0.1f)
        assertEquals(20.0f, rmk.preciseTempCelsius!!, 0.01f)
        assertEquals(14.4f, rmk.preciseDewpointCelsius!!, 0.01f)
        assertEquals(24.4f, rmk.max6HourTempCelsius!!, 0.01f)
        assertEquals(13.3f, rmk.min6HourTempCelsius!!, 0.01f)
        assertEquals(25.6f, rmk.max24HourTempCelsius!!, 0.01f)
        assertEquals(12.2f, rmk.min24HourTempCelsius!!, 0.01f)
        // P0002 -> 0.02 inches = 0.508 mm
        assertEquals(0.508f, rmk.hourlyPrecipMm!!, 0.001f)
    }

    @Test
    fun `decode negative temperatures in T-group and body`() {
        val raw = "SPECI KMSP 151253Z AUTO 34015KT 10SM CLR M05/M12 A3012 RMK AO2 T10561122 11044 21088 $"
        val report = MetarDecoder.decode(raw)

        assertNotNull(report)
        assertEquals(MetarDecoder.ReportType.SPECI, report!!.reportType)
        assertEquals("KMSP", report.stationId)
        assertTrue(report.isAuto)
        assertEquals(-5f, report.bodyTemperatureCelsius)
        assertEquals(-12f, report.bodyDewpointCelsius)
        assertEquals(30.12f, report.altimeterInHg!!, 0.001f)

        val rmk = report.remarks
        assertNotNull(rmk)
        assertTrue(rmk!!.maintenanceNeeded)
        assertEquals(-5.6f, rmk.preciseTempCelsius!!, 0.01f)
        assertEquals(-12.2f, rmk.preciseDewpointCelsius!!, 0.01f)
        assertEquals(-4.4f, rmk.max6HourTempCelsius!!, 0.01f)
        assertEquals(-8.8f, rmk.min6HourTempCelsius!!, 0.01f)
    }

    @Test
    fun `decode KNUQ auto report without T-group`() {
        val raw = "KNUQ 231655Z AUTO 34005KT 10SM CLR 19/15 A2996 RMK AO2"
        val report = MetarDecoder.decode(raw)

        assertNotNull(report)
        assertEquals("KNUQ", report!!.stationId)
        assertTrue(report.isAuto)
        assertEquals(19f, report.bodyTemperatureCelsius)
        assertEquals(15f, report.bodyDewpointCelsius)

        val rmk = report.remarks
        assertNotNull(rmk)
        assertTrue(rmk!!.isAutoStation)
        assertTrue(rmk.hasPrecipDiscriminator)
        assertNull(rmk.preciseTempCelsius)
        assertNull(rmk.preciseDewpointCelsius)
    }

    @Test
    fun `decode SLP with low pressure under 500`() {
        val rmk = MetarDecoder.decodeRemarks("AO2 SLP985 T01500100")
        assertEquals(998.5f, rmk.seaLevelPressureHpa!!, 0.1f)
        assertEquals(15.0f, rmk.preciseTempCelsius!!, 0.01f)
        assertEquals(10.0f, rmk.preciseDewpointCelsius!!, 0.01f)
    }

    @Test
    fun `decode precipitation multi-hour groups`() {
        val rmk = MetarDecoder.decodeRemarks("AO2 60015 70045")
        // 60015 -> 0.15 in = 3.81 mm
        assertEquals(3.81f, rmk.precip3or6HourMm!!, 0.01f)
        // 70045 -> 0.45 in = 11.43 mm
        assertEquals(11.43f, rmk.precip24HourMm!!, 0.01f)
    }
}
