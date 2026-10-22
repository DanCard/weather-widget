package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.HeaderFit.ButtonPlacement
import com.weatherwidget.shared.util.HeaderFit.Measured
import com.weatherwidget.shared.util.HeaderFit.Plan
import com.weatherwidget.shared.util.HeaderFit.Seam
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The header drop order (user, 2026-10-22): source label > temp > rain % > delta > buttons >
 * weather icon > date > "from yest"; the centred buttons end on a fixed line right of the middle,
 * the date just after it. `plans/261022-shared-header-fit-priority.md`.
 */
@Category(ShortDuration::class)
class HeaderFitTest {

    /** A full header; [inline] gives it Android's INLINE rung (desktop has none). */
    private fun full(width: Float, inline: Boolean, rain: Float = 40f) = Measured(
        width = width,
        rightCluster = 60f,
        gap = 6f,
        seamOffset = 20f,
        temp = 60f,
        rain = rain,
        delta = 40f,
        icon = 30f,
        date = 46f,
        caption = 50f,
        baseButtons = 2,
        wantHome = true,
        wantMonths = true,
        centreButton = 30f,
        centreButtonSpacing = 10f,
        inlineButton = if (inline) 30f else 0f,
        inlineLead = 4f,
    )

    @Test
    fun `a roomy row shows everything, but never "from yest" beside a rain percent`() {
        for (inline in listOf(true, false)) {
            val plan = HeaderFit.resolve(full(2000f, inline))
            assertEquals(
                Plan(
                    showTemp = true, showRain = true, showDelta = true, showIcon = true, showDate = true,
                    showCaption = false, buttons = 4, homeShown = true, monthsShown = true,
                    placement = ButtonPlacement.CENTER, seam = Seam.RIGHT_OF_MIDDLE,
                ),
                plan,
            )
            assertTrue("no rain → caption: inline=$inline", HeaderFit.resolve(full(2000f, inline, rain = 0f)).showCaption)
        }
    }

    @Test
    fun `the 360dp emulator widget keeps its weather icon beside three buttons`() {
        // Measured 2026-10-22. The line sits 70dp (183.75px) right of 472.5: three 84px buttons span
        // 404–656, clear of the icon-ended left cluster (375 + 16) and the source label (712).
        // Centred on the middle they started at 346 and the icon had to go.
        val m = Measured(
            width = 945f, rightCluster = 217.25f, gap = 15.75f, seamOffset = HeaderFit.SEAM_OFFSET_DP * 2.625f,
            temp = 129f, rain = 107f, delta = 70.5f, icon = 68.25f, date = 160f, caption = 93.25f,
            baseButtons = 2, wantHome = true, centreButton = 84f, inlineButton = 84f, inlineLead = 2.625f,
        )
        val plan = HeaderFit.resolve(m)
        assertTrue("icon kept: $plan", plan.showIcon)
        assertEquals(ButtonPlacement.CENTER to Seam.RIGHT_OF_MIDDLE, plan.placement to plan.seam)
        assertEquals(3, plan.buttons)
        val (left, right) = HeaderFit.buttonsSpan(m, plan)!!
        assertEquals(656.25f, right, 0.01f)
        assertEquals(404.25f, left, 0.01f)

        val middle = HeaderFit.resolve(m.copy(seamOffset = null))
        assertFalse("on the middle the icon makes way: $middle", middle.showIcon)
    }

    @Test
    fun `on the Fold the home button no longer pushes the date out, and the date does not move`() {
        // The Fold's 662dp widget at ×1.35, measured 2026-10-22. Centred buttons pushed the date's
        // left edge from 1160 to 1229 when home appeared, leaving 200 for a 216 date. Now home
        // grows the row leftwards and the date stays at 1133 either way.
        val m = Measured(
            width = 1737.75f, rightCluster = 293.2875f, gap = 15.75f, seamOffset = HeaderFit.SEAM_OFFSET_DP * 2.625f * 1.35f,
            temp = 174.15f, rain = 144.45f, delta = 95.175f, icon = 92.1375f, date = 216f, caption = 125.8875f,
            baseButtons = 2, centreButton = 136.5f, inlineButton = 84f, inlineLead = 2.625f,
        )
        val withoutHome = HeaderFit.resolve(m)
        val withHome = HeaderFit.resolve(m.copy(wantHome = true))
        val onePanned = HeaderFit.resolve(m.copy(baseButtons = 1, wantHome = true))
        for (plan in listOf(withoutHome, withHome, onePanned)) {
            assertTrue("date shown: $plan", plan.showDate)
            assertTrue("icon shown: $plan", plan.showIcon)
        }
        assertTrue(withHome.homeShown)
        val date = HeaderFit.dateLeft(m, withoutHome)!!
        assertEquals(1132.69f, date, 0.01f)
        assertEquals(date, HeaderFit.dateLeft(m.copy(wantHome = true), withHome)!!, 0f)
        assertEquals(date, HeaderFit.dateLeft(m.copy(baseButtons = 1, wantHome = true), onePanned)!!, 0f)
    }

    @Test
    fun `buttons that do not fit at the line try the middle before going inline`() {
        // The line 80 right of 150 puts their right edge at 230 > 300 - 70 - 6 = 224; on the middle
        // three 40s span 90–210, clear of the 66 the left cluster needs.
        val m = Measured(
            width = 300f, rightCluster = 70f, gap = 6f, seamOffset = 80f, temp = 60f,
            baseButtons = 3, centreButton = 40f, inlineButton = 30f, inlineLead = 4f,
        )
        val plan = HeaderFit.resolve(m)
        assertEquals(ButtonPlacement.CENTER to Seam.MIDDLE, plan.placement to plan.seam)
        assertEquals(90f to 210f, HeaderFit.buttonsSpan(m, plan))
    }

    @Test
    fun `the months button never moves the row off its line`() {
        // Line at 200: two 40s (120–200) clear the 86 the left cluster needs, three (80–200) do not,
        // though three on the middle (90–210) would.
        val m = Measured(
            width = 300f, rightCluster = 70f, gap = 6f, seamOffset = 50f, temp = 80f,
            baseButtons = 2, wantMonths = true, centreButton = 40f, inlineButton = 30f, inlineLead = 4f,
        )
        val plan = HeaderFit.resolve(m)
        assertEquals(ButtonPlacement.CENTER to Seam.RIGHT_OF_MIDDLE, plan.placement to plan.seam)
        assertFalse(plan.monthsShown)
    }

    @Test
    fun `the weather icon drops before the buttons leave their spot`() {
        // Middle only (Android < 12): left with icon = 170 needs the slot to start at 176; two
        // buttons centred on 200 start at 165, so they fit only without the icon (140 + 6 = 146).
        val m = Measured(
            width = 400f, rightCluster = 60f, gap = 6f,
            temp = 60f, rain = 40f, delta = 40f, icon = 30f,
            baseButtons = 2, centreButton = 30f, centreButtonSpacing = 10f, inlineButton = 30f, inlineLead = 4f,
        )
        val plan = HeaderFit.resolve(m)
        assertEquals(ButtonPlacement.CENTER to Seam.MIDDLE, plan.placement to plan.seam)
        assertFalse("icon should have made way for the buttons", plan.showIcon)
        assertTrue(plan.showTemp && plan.showRain && plan.showDelta)
    }

    @Test
    fun `the rain percent stays when the date has no room`() {
        // No buttons: the date's spot is 126 (the middle 120 + gap), pushed to 146 by the rain %,
        // and 146 + 46 overruns 174; without the rain % it would have fitted at 126.
        val m = Measured(width = 240f, rightCluster = 60f, gap = 6f, temp = 60f, rain = 80f, date = 46f)
        val plan = HeaderFit.resolve(m)
        assertTrue(plan.showRain)
        assertFalse(plan.showDate)
        assertTrue(HeaderFit.fits(m, Plan(showTemp = true, showDate = true, seam = Seam.MIDDLE)))
    }

    @Test
    fun `home goes before the base buttons, and months only on the same rung`() {
        // Middle only, no inline rung, left 66 and right 66: the slot fits while it is ≤ width - 132.
        // 2 buttons = 70, 3 = 110, 4 = 150, 5 = 190.
        fun plan(width: Float, base: Int) = HeaderFit.resolve(
            Measured(
                width = width, rightCluster = 60f, gap = 6f, temp = 60f,
                baseButtons = base, wantHome = true, wantMonths = true, centreButton = 30f, centreButtonSpacing = 10f,
            ),
        )
        with(plan(300f, base = 3)) { // room 168
            assertEquals(4, buttons); assertTrue(homeShown); assertFalse(monthsShown)
        }
        with(plan(250f, base = 2)) { // room 118: base 2 + home = 110 fits, months 150 does not
            assertEquals(3, buttons); assertTrue(homeShown); assertFalse(monthsShown)
        }
        with(plan(210f, base = 2)) { // room 78: base only
            assertEquals(2, buttons); assertFalse(homeShown); assertEquals(ButtonPlacement.CENTER, placement)
        }
    }

    @Test
    fun `home may push the row inline, months may not`() {
        // Middle only, 400 wide, left 140: three centred buttons span 200 ± 55 → start at 145 < 146.
        val m = Measured(
            width = 400f, rightCluster = 60f, gap = 6f,
            temp = 60f, rain = 40f, delta = 40f,
            baseButtons = 2, wantHome = true, wantMonths = true,
            centreButton = 30f, centreButtonSpacing = 10f, inlineButton = 30f, inlineLead = 4f,
        )
        val plan = HeaderFit.resolve(m)
        assertEquals(ButtonPlacement.INLINE, plan.placement)
        assertTrue(plan.homeShown)
        assertEquals("months fits inline too (140 + 4 + 120 + 6 = 270 ≤ 340)", 4, plan.buttons)
        assertNull("inline buttons have no centred span", HeaderFit.buttonsSpan(m, plan))
    }

    @Test
    fun `android's painted date never shares the row with inline buttons`() {
        // Centred, 120 of buttons start at 140 < 146, so they go inline (140 + 4 + 60 = 204), which
        // leaves room for a 46 date — but the bitmap cannot see the RemoteViews row, so it goes.
        val m = Measured(
            width = 400f, rightCluster = 60f, gap = 6f,
            temp = 60f, rain = 40f, delta = 40f, date = 46f,
            baseButtons = 2, centreButton = 60f, inlineButton = 30f, inlineLead = 4f,
        )
        val plan = HeaderFit.resolve(m)
        assertEquals(ButtonPlacement.INLINE, plan.placement)
        assertFalse(plan.showDate)
    }

    @Test
    fun `centred buttons must clear the source label too, not just the left cluster`() {
        // Short left cluster (60), but 300 of buttons centred on 220 reach 370 > 440 - 70 - 6.
        val m = Measured(
            width = 440f, rightCluster = 70f, gap = 6f, temp = 60f,
            baseButtons = 2, centreButton = 150f, inlineButton = 30f, inlineLead = 4f,
        )
        assertEquals(ButtonPlacement.INLINE, HeaderFit.resolve(m).placement)
    }

    @Test
    fun `losing a button never makes the placement worse`() {
        // One button is narrower than two, so no row may fall from CENTER to INLINE (or HIDDEN)
        // because the observations button went (today and yesterday panned off screen).
        val rank = mapOf(ButtonPlacement.CENTER to 2, ButtonPlacement.INLINE to 1, ButtonPlacement.HIDDEN to 0)
        for (seamOffset in listOf(null, 0f, 40f, 90f)) for (left in 0..360 step 4) {
            fun placement(base: Int) = HeaderFit.resolve(
                Measured(
                    width = 440f, rightCluster = 71f, gap = 6f, seamOffset = seamOffset, temp = left.toFloat(),
                    baseButtons = base, centreButton = 40f, inlineButton = 30f, inlineLead = 5f,
                ),
            ).placement
            assertTrue("seam=$seamOffset left=$left", rank.getValue(placement(1)) >= rank.getValue(placement(2)))
        }
    }

    @Test
    fun `without centred buttons the date sits on the line, pushed right of a long left cluster`() {
        val m = Measured(width = 400f, rightCluster = 60f, gap = 6f, seamOffset = 30f, temp = 60f, date = 40f)
        val plan = HeaderFit.resolve(m)
        assertTrue(plan.showDate)
        assertEquals(236f, HeaderFit.dateLeft(m, plan)!!, 0.01f)
        val crowded = m.copy(temp = 250f)
        assertEquals(256f, HeaderFit.dateLeft(crowded, HeaderFit.resolve(crowded))!!, 0.01f)
    }

    @Test
    fun `every width keeps the priority - a higher item drops only if it cannot fit above the lower ones`() {
        for (inline in listOf(true, false)) for (rain in listOf(0f, 40f)) {
            var w = 0f
            while (w <= 800f) {
                val m = full(w, inline, rain)
                val plan = HeaderFit.resolve(m)
                val where = "w=$w inline=$inline rain=$rain"
                assertTrue("plan must fit ($where): $plan", HeaderFit.fits(m, plan))
                assertPriorityHeld(m, plan, where)
                assertCentreGroupInBounds(m, plan, where)
                w += 1f
            }
        }
    }

    @Test
    fun `nothing fits a row narrower than the source label`() {
        assertEquals(Plan(), HeaderFit.resolve(full(50f, inline = false)))
    }

    /** For each hidden item: everything above it plus it must not fit. */
    private fun assertPriorityHeld(m: Measured, plan: Plan, where: String) {
        val aboveButtons = Plan(showTemp = plan.showTemp, showRain = plan.showRain, showDelta = plan.showDelta)
        fun mustNotFit(candidate: Plan, what: String) =
            assertFalse("$what was dropped but fits above the lower items ($where): $plan", HeaderFit.fits(m, candidate))

        if (!plan.showTemp) mustNotFit(Plan(showTemp = true), "temp")
        if (!plan.showRain && m.rain > 0f) mustNotFit(Plan(showTemp = plan.showTemp, showRain = true), "rain")
        if (!plan.showDelta) mustNotFit(Plan(showTemp = plan.showTemp, showRain = plan.showRain, showDelta = true), "delta")
        if (plan.buttons == 0) {
            for (seam in Seam.values()) {
                mustNotFit(aboveButtons.copy(buttons = m.baseButtons, placement = ButtonPlacement.CENTER, seam = seam), "buttons on $seam")
            }
            if (m.inlineButton > 0f) {
                mustNotFit(aboveButtons.copy(buttons = m.baseButtons, placement = ButtonPlacement.INLINE), "inline buttons")
            }
        }
        val withButtons = aboveButtons.copy(
            buttons = plan.buttons, homeShown = plan.homeShown, monthsShown = plan.monthsShown,
            placement = plan.placement, seam = plan.seam,
        )
        if (!plan.showIcon) mustNotFit(withButtons.copy(showIcon = true), "icon")
        if (!plan.showDate) mustNotFit(withButtons.copy(showIcon = plan.showIcon, showDate = true), "date")
        if (!plan.showCaption && plan.showDelta && !plan.showRain) {
            mustNotFit(withButtons.copy(showIcon = plan.showIcon, showDate = plan.showDate, showCaption = true), "caption")
        }
        if (plan.showCaption) assertFalse("caption beside rain ($where)", plan.showRain)
    }

    /** The centred buttons and the date never overlap either side, and the date follows the buttons. */
    private fun assertCentreGroupInBounds(m: Measured, plan: Plan, where: String) {
        val left = (if (plan.showIcon) m.icon else 0f) + (if (plan.showTemp) m.temp else 0f) +
            (if (plan.showDelta) m.delta else 0f) + (if (plan.showCaption) m.caption else 0f) + (if (plan.showRain) m.rain else 0f)
        val rightLimit = m.width - m.rightCluster - m.gap + 0.01f
        val span = HeaderFit.buttonsSpan(m, plan)
        span?.let { (l, r) ->
            assertTrue("buttons start inside the left cluster ($where): $plan", l >= left + m.gap - 0.01f)
            assertTrue("buttons run into the source label ($where): $plan", r <= rightLimit)
        }
        HeaderFit.dateLeft(m, plan)?.let { d ->
            assertTrue("date starts inside the left cluster ($where): $plan", d >= left + m.gap - 0.01f)
            assertTrue("date runs into the source label ($where): $plan", d + m.date <= rightLimit)
            span?.let { (_, r) -> assertTrue("date overlaps the buttons ($where): $plan", d >= r + m.gap - 0.01f) }
        }
    }
}
