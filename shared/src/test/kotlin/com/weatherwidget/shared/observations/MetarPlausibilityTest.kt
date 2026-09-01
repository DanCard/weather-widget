package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The two corrupt reports here are verbatim from the device database (Samsung SM-F936U1,
 * 2026-08-31 backup), together with their own valid neighbours an hour either side. They are the
 * only three rows out of 7,229 stored METARs that any of these rules reject.
 */
@Category(ShortDuration::class)
class MetarPlausibilityTest {

    // KPAO 2026-08-31 16:47 local. `10/12` — dewpoint 12 C above a temperature of 10 C. Stored as
    // 50.0 F between neighbours reading 69.8 F, and blended at full weight until this check existed.
    private val kpaoCorrupt = "METAR KPAO 312347Z 32014G22KT 10SM SCT040 10/12 A2993"
    private val kpaoValid = "KPAO 312247Z 33018G20KT 10SM SCT040 21/12 A2993"

    // KRHV 2026-08-27 08:47 local. `209/14` — a three-digit temperature field. Upstream salvaged the
    // trailing `09` -> 9 C -> 48.2 F, between neighbours of 66.2 F and 73.4 F.
    private val krhvCorrupt = "METAR KRHV 271547Z 00000KT 10SM FEW080 209/14 A2996"
    private val krhvValidBefore = "METAR KRHV 271447Z 00000KT 10SM SCT080 19/13 A2997"
    private val krhvValidAfter = "METAR KRHV 271647Z 35003KT 10SM SCT080 23/14 A2997"

    @Test
    fun `dewpoint above temperature is rejected`() {
        val verdict = MetarPlausibility.check(50.0f, kpaoCorrupt)
        assertTrue("KPAO 10/12 must fail", verdict.failed)
        assertEquals(MetarPlausibility.REASON_DEWPOINT_ABOVE_TEMP, verdict.reason)
    }

    @Test
    fun `malformed temperature group is rejected`() {
        val verdict = MetarPlausibility.check(48.2f, krhvCorrupt)
        assertTrue("KRHV 209/14 must fail", verdict.failed)
        assertEquals(MetarPlausibility.REASON_MALFORMED_TEMP_GROUP, verdict.reason)
    }

    /**
     * The neighbours of both corrupt reports. If these ever fail, the check is deleting real
     * weather — the failure mode that made a temperature-jump rule unusable here.
     */
    @Test
    fun `the valid neighbours of both corrupt reports pass`() {
        assertFalse(MetarPlausibility.check(69.8f, kpaoValid).failed)
        assertFalse(MetarPlausibility.check(66.2f, krhvValidBefore).failed)
        assertFalse(MetarPlausibility.check(73.4f, krhvValidAfter).failed)
    }

    @Test
    fun `dewpoint equal to temperature passes`() {
        // Saturated air — fog, and entirely ordinary.
        assertFalse(MetarPlausibility.check(53.6f, "KSFO 311847Z 00000KT 1/4SM FG 12/12 A2998").failed)
    }

    @Test
    fun `negative temperatures and missing dewpoints pass`() {
        assertFalse(MetarPlausibility.check(23.0f, "KBOI 311847Z 09003KT 10SM CLR M05/M12 A3012").failed)
        assertFalse(MetarPlausibility.check(50.0f, "KPAO 312347Z 32014KT 10SM SCT040 10/// A2993").failed)
        assertFalse(MetarPlausibility.check(50.0f, "KPAO 312347Z 32014KT 10SM SCT040 10// A2993").failed)
    }

    /** M-negated dewpoint above an M-negated temperature is still impossible. */
    @Test
    fun `negative dewpoint above negative temperature is rejected`() {
        val verdict = MetarPlausibility.check(5.0f, "KBOI 311847Z 09003KT 10SM CLR M15/M05 A3012")
        assertTrue(verdict.failed)
        assertEquals(MetarPlausibility.REASON_DEWPOINT_ABOVE_TEMP, verdict.reason)
    }

    /**
     * Visibility fractions and RVR groups are the two other slash-bearing shapes in a METAR body.
     * Neither may be mistaken for the temperature group.
     */
    @Test
    fun `visibility fractions and RVR groups are not read as temperature groups`() {
        assertFalse(MetarPlausibility.check(53.6f, "KSFO 311847Z 00000KT 1/2SM FG 12/12 A2998").failed)
        assertFalse(MetarPlausibility.check(53.6f, "KSFO 311847Z 00000KT 1 1/2SM R28L/2600FT 12/12 A2998").failed)
    }

    /** Remarks carry coded groups that are not T/Td pairs; the body is what gets read. */
    @Test
    fun `remarks are not scanned for a temperature group`() {
        assertFalse(
            MetarPlausibility.check(69.8f, "$kpaoValid RMK AO2 SLP134 T02110122 10233 20172").failed,
        )
    }

    @Test
    fun `a reading with no raw report is judged only on range`() {
        assertFalse(MetarPlausibility.check(69.8f, null).failed)
        assertFalse(MetarPlausibility.check(69.8f, "").failed)
        assertTrue(MetarPlausibility.check(400f, null).failed)
    }

    @Test
    fun `absurd temperatures are rejected and real extremes are kept`() {
        assertEquals(
            MetarPlausibility.REASON_OUT_OF_RANGE,
            MetarPlausibility.check(200f, null).reason,
        )
        assertTrue(MetarPlausibility.check(Float.NaN, null).failed)
        // Verkhoyansk and Death Valley are legitimate readings, not corruption.
        assertFalse(MetarPlausibility.check(-70f, null).failed)
        assertFalse(MetarPlausibility.check(130f, null).failed)
    }
}
