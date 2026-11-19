package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HeaderFitTest {

    private fun sample(
        width: Float,
        rightCluster: Float = 80f,
        gap: Float = 6f,
        temp: Float = 55f,
        rain: Float = 40f,
        delta: Float = 30f,
        icon: Float = 26f,
        date: Float = 50f,
        caption: Float = 42f,
        baseButtons: Int = 2,
        wantHome: Boolean = false,
        wantMonths: Boolean = false,
        buttonWidth: Float = 32f,
        minButtonWidth: Float = 26f,
        buttonSpacing: Float = 0f,
    ) = HeaderFit.Measured(
        width = width,
        rightCluster = rightCluster,
        gap = gap,
        temp = temp,
        rain = rain,
        delta = delta,
        icon = icon,
        date = date,
        caption = caption,
        baseButtons = baseButtons,
        wantHome = wantHome,
        wantMonths = wantMonths,
        buttonWidth = buttonWidth,
        minButtonWidth = minButtonWidth,
        buttonSpacing = buttonSpacing,
    )

    @Test
    fun `roomy row keeps everything except caption beside rain and right-anchors buttons`() {
        val m = sample(width = 520f, wantHome = true, wantMonths = true)
        val plan = HeaderFit.resolve(m)

        assertTrue(plan.showTemp)
        assertTrue(plan.showRain)
        assertTrue(plan.showDelta)
        assertTrue(plan.showIcon)
        assertTrue(plan.showDate)
        assertFalse("caption never shows beside rain %", plan.showCaption)
        assertEquals(HeaderFit.ButtonPlacement.SHOWN, plan.placement)
        assertEquals(4, plan.buttons)
        assertTrue(plan.homeShown)
        assertTrue(plan.monthsShown)
        assertEquals(32f, plan.buttonZone, 0.01f)

        val (bLeft, bRight) = HeaderFit.buttonsSpan(m, plan)!!
        assertEquals("buttons right-anchored against rightCluster + gap", 520f - 80f - 6f, bRight, 0.01f)
        assertEquals(bRight - 4 * 32f, bLeft, 0.01f)

        val dLeft = HeaderFit.dateLeft(m, plan)!!
        assertEquals("date pinned to widget horizontal center", (m.width - m.date) / 2f, dLeft, 0.01f)
    }

    @Test
    fun `adding or removing home keeps rightmost button edge pinned`() {
        val withoutHome = sample(width = 373f, wantHome = false, date = 0f)
        val withHome = withoutHome.copy(wantHome = true)

        val planA = HeaderFit.resolve(withoutHome)
        val planB = HeaderFit.resolve(withHome)
        assertEquals(2, planA.buttons)
        assertEquals(3, planB.buttons)

        val (leftA, rightA) = HeaderFit.buttonsSpan(withoutHome, planA)!!
        val (leftB, rightB) = HeaderFit.buttonsSpan(withHome, planB)!!
        assertEquals("right edge stays pinned when home appears", rightA, rightB, 0.01f)
        assertTrue("buttons grow leftward when home appears", leftB < leftA)
    }

    @Test
    fun `buttons narrow evenly down to minButtonWidth before dropping optional buttons`() {
        // Left cluster (temp+rain+delta = 125) + gap(6) = 131; right edge - gap = 220 - 80 - 6 = 134 -> too tight
        // Choose width = 295: rightEdge - gap = 209; left(125) + gap(6) = 131; corridor = 78.
        // 3 buttons at 32 = 96 > 78, at min 26 = 78 -> fits at exactly 26!
        val m = sample(width = 295f, icon = 0f, date = 0f, caption = 0f, baseButtons = 2, wantHome = true)
        val plan = HeaderFit.resolve(m)

        assertTrue(plan.homeShown)
        assertEquals(3, plan.buttons)
        assertEquals(26f, plan.buttonZone, 0.01f)
    }

    @Test
    fun `date and caption never narrow buttons`() {
        // Corridor = (373 - 80 - 6) - (125 + 26 + 6) = 287 - 157 = 130.
        // 3 buttons at 32 = 96; remaining for date = 130 - 96 - 6 = 28 < 50 (date).
        // If date were allowed to narrow buttons to 26 (78), remaining = 46 (still < 50), or with date = 40 it WOULD fit
        // only by narrowing buttons — verify date is dropped instead of narrowing buttons.
        val m = sample(width = 373f, date = 40f, rain = 0f, baseButtons = 2, wantHome = true)
        // Left cluster without rain: icon(26)+temp(55)+delta(30) = 111. corridor = 287 - 117 = 170.
        // Pick tighter width = 320: rightEdge-gap = 234, left+gap = 117, corridor = 117.
        // 3 buttons at 32 = 96 (leaves 15 < 40). At 26 = 78 (leaves 33 < 40). Try date = 25:
        // at 32 leaves 15 (<25); at 26 leaves 33 (>=25). Date must NOT narrow buttons!
        val tight = m.copy(width = 320f, date = 25f, caption = 25f)
        val plan = HeaderFit.resolve(tight)

        assertEquals("buttons keep full 32 width", 32f, plan.buttonZone, 0.01f)
        assertFalse("date dropped rather than narrowing buttons", plan.showDate)
        assertFalse("caption dropped rather than narrowing buttons", plan.showCaption)
    }

    @Test
    fun `caption shows when dry and roomy`() {
        val m = sample(width = 450f, rain = 0f, wantHome = true)
        val plan = HeaderFit.resolve(m)
        assertTrue(plan.showDelta)
        assertFalse(plan.showRain)
        assertTrue(plan.showCaption)
    }

    @Test
    fun `narrow widths drop lower priority items in order and never overlap`() {
        for (w in 120..500 step 5) {
            val m = sample(width = w.toFloat(), wantHome = true, wantMonths = true)
            val plan = HeaderFit.resolve(m)
            val left = (if (plan.showIcon) m.icon else 0f) +
                (if (plan.showTemp) m.temp else 0f) +
                (if (plan.showDelta) m.delta else 0f) +
                (if (plan.showCaption) m.caption else 0f) +
                (if (plan.showRain) m.rain else 0f)
            val rightLimit = m.width - m.rightCluster - m.gap + 0.01f

            HeaderFit.buttonsSpan(m, plan)?.let { (bLeft, bRight) ->
                assertTrue("width=$w: buttons overlap left cluster ($bLeft < ${left + m.gap})", bLeft >= left + m.gap - 0.01f)
                assertTrue("width=$w: buttons overlap right cluster ($bRight > $rightLimit)", bRight <= rightLimit)
            }
            HeaderFit.dateLeft(m, plan)?.let { dLeft ->
                assertTrue("width=$w: date overlaps left cluster", dLeft >= left + m.gap - 0.01f)
                val maxDateRight = HeaderFit.buttonsSpan(m, plan)?.first?.minus(m.gap) ?: rightLimit
                assertTrue("width=$w: date overlaps buttons/right cluster", dLeft + m.date <= maxDateRight + 0.01f)
            }
        }
    }

    @Test
    fun `no buttons returns null buttonsSpan and pins date to widget center`() {
        val m = sample(width = 300f, baseButtons = 0, date = 50f, rain = 0f, icon = 0f, caption = 0f)
        val plan = HeaderFit.resolve(m)
        assertEquals(HeaderFit.ButtonPlacement.HIDDEN, plan.placement)
        assertNull(HeaderFit.buttonsSpan(m, plan))
        assertTrue(plan.showDate)
        val dLeft = HeaderFit.dateLeft(m, plan)!!
        assertEquals((m.width - m.date) / 2f, dLeft, 0.01f)
    }
}
