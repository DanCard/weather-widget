package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.DailyRainLabelPlanner.Box
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyRainLabelLayoutTest {

    /** Every label measures 10 px per character × its font scale, 14 px tall, baseline 11 px down. */
    private val host = DailyRainLabelLayout.Host { text, _, fontScale ->
        DailyRainLabelLayout.TextSize(width = text.length * 10f * fontScale, height = 14f, baseline = 11f)
    }

    // Fold 4, history Tue 10-27 → Wed 10-28 (logcat 2026-10-29 09:33, density 2.625): Tue "48°"
    // ends at 379.6; Wed "45.7°" starts at 383.7 and ends at 416.9, below the day-name row's top.
    private val tueLow = Box(3.9f, 346.4f, 45.9f, 379.6f)
    private val wedLow = Box(42.3f, 383.7f, 107.3f, 416.9f)
    private val foldObstacles = listOf(
        Box(7.4f, 308.8f, 42.4f, 343.8f), // Tue icon
        Box(3.9f, 346.4f, 35.9f, 379.6f), Box(35.9f, 346.4f, 45.9f, 363.0f), // Tue "48" + °
        Box(57.3f, 346.1f, 92.3f, 381.1f), // Wed icon
        Box(42.3f, 383.7f, 97.3f, 416.9f), Box(97.3f, 383.7f, 107.3f, 400.3f), // Wed "45.7" + °
    )

    /** "10%" as the Fold measured it (size=20.0x14.0). */
    private val foldHost = DailyRainLabelLayout.Host { _, _, _ ->
        DailyRainLabelLayout.TextSize(width = 20f, height = 14f, baseline = 11f)
    }

    private fun foldNight(dayLabelTop: Float) = DailyRainLabelLayout.layoutNight(
        foldHost,
        DailyRainLabelLayout.NightInput(
            date = "2026-10-27",
            text = "10%",
            probability = 10,
            isPast = true,
            daysFromToday = -2,
            columnCenterX = 23.4f,
            gapCenterX = 49.9f,
            columnWidth = 53f,
            leftLowLabel = tueLow,
            rightLowLabel = wedLow,
            obstacles = foldObstacles,
            dayLabelTop = dayLabelTop,
            canvasWidth = 560f,
            pxPerDp = 2.625f,
        ),
        "test",
    )

    // Pixel 7 Pro 2026-11-06 19:36 (Silurian, canvas 517 px, 2.63 px/dp): Sat, the last column,
    // "100%" centred under Sat's own "57°" sat at 343.2, 6 dp under the digits' ink (bottom 336.6
    // less 22 % blank descent = 327.4). It now tucks to 1 dp under that ink.
    private fun pixelSatNight(obstacles: List<Box>) = DailyRainLabelLayout.layoutNight(
        DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(47f, 22.5f, 17f) },
        DailyRainLabelLayout.NightInput(
            date = "2026-11-14", text = "100%", probability = 100, isPast = false, daysFromToday = 8,
            columnCenterX = 480.1f, gapCenterX = 517f, columnWidth = 73.9f,
            leftLowLabel = Box(453.6f, 295.0f, 506.6f, 336.6f), rightLowLabel = null,
            obstacles = obstacles, dayLabelTop = 395.7f, canvasWidth = 517f, pxPerDp = 2.627f,
        ),
        "test",
    )

    private val pixelSatObstacles = listOf(
        Box(453.6f, 242.0f, 506.6f, 295.0f), // Sat icon
        Box(453.6f, 295.0f, 493.6f, 336.6f, isLowDigits = true), // "57"
        Box(493.6f, 295.0f, 506.6f, 315.8f, isDegreeSign = true), // °
    )

    @Test
    fun `last column night label centred under its own low tucks up to 1 dp under the digits' ink`() {
        val label = pixelSatNight(pixelSatObstacles)
        val inkBottom = 336.6f - (336.6f - 295.0f) * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION
        println("Pixel Sat 100%: fit=${label.placement} top=${label.top} (was 343.2) inkBottom=$inkBottom")
        assertEquals("NIGHT_CENTERED", label.placement)
        assertEquals(inkBottom + 2.627f, label.top, 0.01f)
        assertEquals(label.top + 17f, label.baseline, 0.01f)
    }

    @Test
    fun `centred night label stops rising at other ink in the way`() {
        // Something drawn between the label and the low: the label stops 1 dp under its ink.
        val label = pixelSatNight(pixelSatObstacles + Box(470f, 332f, 500f, 338f))
        println("Pixel Sat 100% under a blocker: top=${label.top}")
        // Rises in 0.5 dp steps, so it stops within one step of 1 dp under the blocker.
        val clear = 338f + 2.627f
        assertTrue("top ${label.top} should be in [$clear, ${clear + 0.5f * 2.627f}]", label.top >= clear && label.top <= clear + 0.5f * 2.627f)
        assertTrue("still rose from 343.2", label.top < 343.2f)
    }

    @Test
    fun `fold night label pinned on the floor under the left low nudges right and down`() {
        // 2026-10-31 Fold NWS trace: "48%" touched the 9 of Today's "47.9°", 2 dp left of centre.
        val leftLow = Box(85.6f, 338.2f, 156.6f, 375.1f)
        val rightLow = Box(150.0f, 336.8f, 196.0f, 373.7f)
        val p = 2.6f
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(26f, 16.7f, 13f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = "48%", probability = 48, isPast = false, daysFromToday = 0,
                columnCenterX = 115f, gapCenterX = 147f, columnWidth = 52f,
                leftLowLabel = leftLow, rightLowLabel = rightLow,
                obstacles = listOf(
                    Box(105.6f, 307.5f, 136.6f, 338.5f),
                    Box(85.6f, 338.2f, 144.6f, 375.1f, isLowDigits = true),
                    Box(144.6f, 338.2f, 156.6f, 356.6f, isDegreeSign = true),
                    Box(157.5f, 306.1f, 188.5f, 337.1f),
                    Box(150.0f, 336.8f, 184.0f, 373.7f, isLowDigits = true),
                    Box(184.0f, 336.8f, 196.0f, 355.2f, isDegreeSign = true),
                ),
                dayLabelTop = 379.4f, canvasWidth = 664f, pxPerDp = p,
            ),
            "test",
        )
        assertEquals(141.8f + DailyRainLabels.NIGHT_FLOOR_SLIDE_DP * p, label.centerX, 0.05f)
        assertEquals(379.4f - 16.7f + DailyRainLabels.NIGHT_FLOOR_DROP_DP * p, label.top, 0.05f)
    }

    @Test
    fun `desktop night label stuck on the floor against a degree sign drops into its blank descent`() {
        // 2026-10-31 desktop OWM trace: "37%" sat on the ° of "47.6°", 17 px above the day name's ink.
        val degree = Box(308.8f, 799f, 325.8f, 824.5f, isDegreeSign = true)
        fun drop(top: Float, blank: Float, obstacles: List<Box> = listOf(degree)) = DailyRainLabelPlanner.dropOffDegreeAtFloor(
            centerX = 341.2f, top = top, width = 50f, height = 34f, blankBelowPx = blank,
            obstacles = obstacles, maxBottom = 843f, density = 3f,
        )
        assertEquals("3 dp", 818f, drop(809f, 12f), 0.01f)
        assertEquals("blank descent", 816f, drop(809f, 7f), 0.01f)
        assertEquals("room above the floor", 800f, drop(800f, 12f), 0.01f)
        assertEquals("nearly clear of the °", 809f, drop(809f, 12f, listOf(degree.copy(bottom = 812f))), 0.01f)
        assertEquals("no ° level", 809f, drop(809f, 12f, listOf(degree.copy(left = 100f, right = 120f))), 0.01f)
    }

    @Test
    fun `floor ease leaves a label that stayed centred, is above the floor, or is clear of the digits`() {
        val digits = Box(85.6f, 338.2f, 144.6f, 375.1f, isLowDigits = true)
        val low = Box(85.6f, 338.2f, 156.6f, 375.1f)
        fun ease(centerX: Float, top: Float, home: Float = 147f) = DailyRainLabelPlanner.easeOffLeftLowAtFloor(
            homeCenterX = home, centerX = centerX, top = top, width = 26f, height = 16.7f,
            leftLowLabel = low, obstacles = listOf(digits), maxBottom = 379.4f, density = 2.6f,
        )
        assertEquals("centred", DailyRainLabelPlanner.NightLabelEase(147f, 362.7f), ease(147f, 362.7f))
        assertEquals("room below", DailyRainLabelPlanner.NightLabelEase(141.8f, 355f), ease(141.8f, 355f))
        assertEquals("clear of digits", DailyRainLabelPlanner.NightLabelEase(160f, 362.7f), ease(160f, 362.7f, home = 170f))
    }

    @Test
    fun `fold today night label too wide for the gap sinks below both lows and slides right`() {
        // 2026-10-31 Fold trace: "40%" sat on the 9 and the ° of Today's "47.9°", with 6.5 dp free below.
        val leftLow = Box(131.7f, 323.4f, 190.7f, 360.3f)
        val rightLow = Box(196.1f, 321.9f, 230.1f, 358.8f)
        val p = 2.6f
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(26f, 16.6f, 13f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = "40%", probability = 40, isPast = false, daysFromToday = 0,
                columnCenterX = 167f, gapCenterX = 193.1f, columnWidth = 49f,
                leftLowLabel = leftLow, rightLowLabel = rightLow,
                obstacles = listOf(
                    Box(151.7f, 292.8f, 182.7f, 323.8f), leftLow,
                    Box(190.7f, 323.4f, 202.7f, 341.9f, isDegreeSign = true),
                    Box(203.6f, 291.2f, 234.6f, 322.2f), rightLow,
                    Box(230.1f, 321.9f, 242.1f, 340.3f, isDegreeSign = true),
                ),
                dayLabelTop = 379.4f, canvasWidth = 480f, pxPerDp = p,
            ),
            "test",
        )
        val slight = DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * p
        assertTrue("below the 47.9: top=${label.top}", label.top >= leftLow.bottom - 2f * slight)
        assertTrue("clear of the day names: bottom=${label.bottom}", label.bottom <= 379.4f)
        assertEquals(193.1f + DailyRainLabels.NIGHT_SINK_RIGHT_DP * p, label.centerX, 0.01f)
    }

    @Test
    fun `sunk night label stops short of a low level with it`() {
        val wall = Box(110f, 100f, 150f, 130f)
        val cx = DailyRainLabelPlanner.slideRightAfterSinking(
            centerX = 100f, top = 105f, width = 16f, height = 14f, leftLowLabel = Box(50f, 90f, 105f, 125f),
            obstacles = listOf(wall), maxRight = 400f, density = 2f,
        )
        assertEquals(102f, cx, 0.01f)
    }

    private val foldGoogleHost = DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(20f, 14f, 11f) }

    /** Google on the Fold, 2026-10-31 09:44 trace; low labels include their °. */
    private fun foldGoogleNight(gap: Float, leftLow: Box, rightLow: Box, obstacles: List<Box>) =
        DailyRainLabelLayout.layoutNight(
            foldGoogleHost,
            DailyRainLabelLayout.NightInput(
                date = "2026-10-30", text = "10%", probability = 10, isPast = true, daysFromToday = -1,
                columnCenterX = gap - 23f, gapCenterX = gap, columnWidth = 46f,
                leftLowLabel = leftLow, rightLowLabel = rightLow, obstacles = obstacles,
                dayLabelTop = 379.4f, canvasWidth = 560f, pxPerDp = 2.625f,
            ),
            "test",
        )

    @Test
    fun `sunk night label already clear of the left low stays centred`() {
        // Fri-Today "10%": sits below Fri's low, grazing Today's; the fixed slide moved it +3 dp.
        val fri = Box(82.8f, 306.3f, 147.8f, 339.5f)
        val today = Box(131.7f, 314.3f, 202.7f, 351.2f)
        val label = foldGoogleNight(
            141.3f, fri, today,
            listOf(
                Box(99.8f, 272.7f, 130.8f, 303.7f), Box(82.8f, 306.3f, 137.8f, 339.5f),
                Box(137.8f, 306.3f, 147.8f, 322.9f, isDegreeSign = true),
                Box(151.7f, 283.7f, 182.7f, 314.7f), Box(131.7f, 314.3f, 190.7f, 351.2f),
                Box(190.7f, 314.3f, 202.7f, 332.8f, isDegreeSign = true),
            ),
        )
        assertEquals(141.3f, label.centerX, 0.01f)
    }

    @Test
    fun `night label with room below drops under overlapping lows and stays centred`() {
        // Thu-Fri "15%": the wide lows overlap across the gap; going right put it under Fri's column.
        val thu = Box(36.7f, 315.9f, 101.7f, 349.1f)
        val fri = Box(82.8f, 306.3f, 147.8f, 339.5f)
        val label = foldGoogleNight(
            92.2f, thu, fri,
            listOf(
                Box(53.7f, 282.3f, 84.7f, 313.3f), Box(36.7f, 315.9f, 91.7f, 349.1f),
                Box(91.7f, 315.9f, 101.7f, 332.5f, isDegreeSign = true),
                Box(99.8f, 272.7f, 130.8f, 303.7f), Box(82.8f, 306.3f, 137.8f, 339.5f),
                Box(137.8f, 306.3f, 147.8f, 322.9f, isDegreeSign = true),
            ),
        )
        assertEquals(92.2f, label.centerX, 0.01f)
        assertTrue("under Thu's digits: top=${label.top}", label.top >= 349.1f - DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * 2.625f)
    }

    // Fold 4, NWS, 2026-11-14 11:51 trace (2.625 px/dp): Tue night ".3in" between Tue "42°", the
    // week's coldest low (its line box ends 4.2 px below the day-name floor), and Wed "47°". It went
    // 5 dp right and sat under Wed's digits, reading as a Wednesday label.
    private fun foldTueNight(measure: DailyRainLabelLayout.Host) = DailyRainLabelLayout.layoutNight(
        measure,
        DailyRainLabelLayout.NightInput(
            date = "2026-11-17", text = ".3in", probability = 99, isPast = false, daysFromToday = 3,
            columnCenterX = 339f, gapCenterX = 364.1f, columnWidth = 50.2f,
            leftLowLabel = Box(316.0f, 344.8f, 362.0f, 381.8f), rightLowLabel = Box(366.2f, 306.1f, 412.2f, 343.0f),
            obstacles = listOf(
                Box(323.5f, 314.2f, 354.5f, 345.2f), // Tue icon
                Box(316.0f, 344.8f, 350.0f, 381.8f, isLowDigits = true), Box(350.0f, 344.8f, 362.0f, 363.3f, isDegreeSign = true),
                Box(373.7f, 275.4f, 404.7f, 306.4f), // Wed icon
                Box(366.2f, 306.1f, 400.2f, 343.0f, isLowDigits = true), Box(400.2f, 306.1f, 412.2f, 324.5f, isDegreeSign = true),
            ),
            dayLabelTop = 377.6f, canvasWidth = 560f, pxPerDp = 2.625f,
        ),
        "test",
    )

    private val tueNightBaseScale =
        DailyRainLabels.rainLabelFontScale(false, 99, 3) * DailyRainLabels.NIGHT_SCALE

    @Test
    fun `night label tucks under its own day's degree sign instead of the next day's low`() {
        // Android measures 19.9 px of line box with 4.1 px of blank descent under the baseline.
        val label = foldTueNight { _, _, _ -> DailyRainLabelLayout.TextSize(27f, 19.9f, 15.8f) }
        println("fold .3in: centerX=${label.centerX} top=${label.top} bottom=${label.bottom}")
        // In the gap at 364.1, then 1.5 dp off Tue's "42" (2026-11-15 pocket side gap).
        assertEquals("1.5 dp right of Tue's digits", 350.0f + 1.5f * 2.625f, label.left, 0.01f)
        assertTrue("still under Tue's °, not Wed's digits: left=${label.left}", label.left < 362.0f)
        assertTrue("under Tue's °: top=${label.top}", label.top >= 363.3f - 1.5f * 2.625f)
        assertTrue("no lower than Tue's low: bottom=${label.bottom}", label.bottom <= 381.8f)
        assertTrue("only blank descent passes the day-name floor: bottom=${label.bottom}", label.bottom - 377.6f <= 4.1f + 0.01f)
        assertEquals("full size", tueNightBaseScale, label.fontScale, 0.0001f)
    }

    @Test
    fun `emulator night label tucks under its own day's degree sign`() {
        // emulator-5554, NWS, 2026-11-14 11:56 trace: same week, ".3in" resolved (310.1, 384.2), 1.3 px
        // under Wed's digits.
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(27f, 19.9f, 15.8f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-11-17", text = ".3in", probability = 99, isPast = false, daysFromToday = 3,
                columnCenterX = 280.5f, gapCenterX = 304.9f, columnWidth = 48.8f,
                leftLowLabel = Box(257.5f, 398.0f, 303.5f, 434.9f), rightLowLabel = Box(306.3f, 348.6f, 352.3f, 385.5f),
                obstacles = listOf(
                    Box(265.0f, 367.3f, 296.0f, 398.3f),
                    Box(257.5f, 398.0f, 291.5f, 434.9f, isLowDigits = true), Box(291.5f, 398.0f, 303.5f, 416.5f, isDegreeSign = true),
                    Box(313.8f, 318.0f, 344.8f, 349.0f),
                    Box(306.3f, 348.6f, 340.3f, 385.5f, isLowDigits = true), Box(340.3f, 348.6f, 352.3f, 367.1f, isDegreeSign = true),
                ),
                dayLabelTop = 430.7f, canvasWidth = 540f, pxPerDp = 2.625f,
            ),
            "test",
        )
        println("emulator .3in: centerX=${label.centerX} top=${label.top} bottom=${label.bottom}")
        assertEquals("1.5 dp right of Tue's digits", 291.5f + 1.5f * 2.625f, label.left, 0.01f)
        assertTrue("under Tue's °: top=${label.top}", label.top >= 416.5f - 1.5f * 2.625f)
        assertTrue("no lower than Tue's low: bottom=${label.bottom}", label.bottom <= 434.9f)
    }

    @Test
    fun `night label under the next low's blank descent stays centred between the two days`() {
        // emulator-5554, NWS, 2026-11-14 12:01 trace: Wed night "87%" dropped 2 dp at the gap centre,
        // below the ink of Thu "46°" (line box to 395.4, ink to 387.3), then eased 5.6 dp left, the
        // whole 30 % shift, into Wed's column: resolved (339.0, 389.5).
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(30f, 19f, 15f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-11-18", text = "87%", probability = 87, isPast = false, daysFromToday = 4,
                columnCenterX = 329.3f, gapCenterX = 353.7f, columnWidth = 48.8f,
                leftLowLabel = Box(306.3f, 348.6f, 352.3f, 385.5f), rightLowLabel = Box(355.0f, 358.5f, 401.0f, 395.4f),
                obstacles = listOf(
                    Box(313.8f, 318.0f, 344.8f, 349.0f),
                    Box(306.3f, 348.6f, 340.3f, 385.5f, isLowDigits = true), Box(340.3f, 348.6f, 352.3f, 367.1f, isDegreeSign = true),
                    Box(362.5f, 327.9f, 393.5f, 358.9f),
                    Box(355.0f, 358.5f, 389.0f, 395.4f, isLowDigits = true), Box(389.0f, 358.5f, 401.0f, 377.0f, isDegreeSign = true),
                ),
                dayLabelTop = 430.7f, canvasWidth = 540f, pxPerDp = 2.625f,
            ),
            "test",
        )
        println("emulator 87%: centerX=${label.centerX} top=${label.top}")
        assertEquals("centred between Wed and Thu", 353.7f, label.centerX, 0.01f)
        val thuInkBottom = 395.4f - (395.4f - 358.5f) * DailyRainLabels.DIGIT_BLANK_DESCENT_FRACTION
        assertTrue("below Thu's digit ink: top=${label.top}", label.top >= thuInkBottom)
    }

    @Test
    fun `night label whose ink would cross the day names keeps its place beside the next low`() {
        // No blank descent to spend, and a host that cannot shrink: the pocket stays out of reach.
        val label = foldTueNight { _, _, _ -> DailyRainLabelLayout.TextSize(27f, 19.9f, 19.9f) }
        assertEquals("5 dp right, as before", 364.1f + 5f * 2.625f, label.centerX, 0.01f)
        assertTrue("above the day names: bottom=${label.bottom}", label.bottom <= 377.6f)
    }

    @Test
    fun `night label shrinks when only the smaller size fits under its own degree sign`() {
        // 1 px of blank descent is too little at full size (the pocket needs 1.7 px past the floor);
        // at 0.85 the label is 3 px shorter and fits.
        val label = foldTueNight { _, _, fontScale ->
            val k = fontScale / tueNightBaseScale
            DailyRainLabelLayout.TextSize(27f * k, 19.9f * k, 18.9f * k)
        }
        println("fold .3in shrunk: centerX=${label.centerX} top=${label.top} bottom=${label.bottom} scale=${label.fontScale}")
        assertEquals(tueNightBaseScale * DailyRainLabelLayout.NIGHT_REDUCED_SCALE, label.fontScale, 0.0001f)
        assertEquals("NIGHT_SHIFTED_SCALED", label.placement)
        assertEquals("1.5 dp right of Tue's digits", 350.0f + 1.5f * 2.625f, label.left, 0.01f)
        assertTrue("under Tue's °: top=${label.top}", label.top >= 363.3f - 1.5f * 2.625f)
    }

    @Test
    fun `pocket rows are not tried for a label that is clear of the next day's low`() {
        val degree = Box(50f, 100f, 60f, 110f, isDegreeSign = true)
        val placed = DailyRainLabelPlanner.placeNightLabel(
            homeCenterX = 62f, homeTop = 104f, width = 20f, height = 10f,
            obstacles = listOf(degree), maxShiftPx = 20f, maxBottom = 116f, minLeft = 0f, maxRight = 200f, density = 1f,
            pocketDegree = degree, pocketMaxBottom = 124f, rightDigits = Box(120f, 60f, 150f, 90f, isLowDigits = true),
        )
        assertTrue("stays above the floor: top=${placed.top}", placed.top + 10f <= 116f)
        assertEquals(false, placed.inPocket)
    }

    // Fold 4, Open-Meteo, 2026-11-15 06:47 trace: Tue night "97%" took the pocket under Tue's "45°"
    // at (313.9, 360.3); its left edge sat 0.9 px inside the "45" box and read "45°97%" on screen.
    private val foldMeteoTueDigits = Box(265.8f, 344.8f, 299.8f, 381.8f, isLowDigits = true)
    private val foldMeteoWedDigits = Box(316.0f, 303.2f, 350.0f, 340.1f, isLowDigits = true)
    private fun foldMeteoTueNight() = DailyRainLabelLayout.layoutNight(
        DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(30f, 19.8f, 15.7f) },
        DailyRainLabelLayout.NightInput(
            date = "2026-11-17", text = "97%", probability = 97, isPast = false, daysFromToday = 2,
            columnCenterX = 288.8f, gapCenterX = 313.9f, columnWidth = 50.2f,
            leftLowLabel = Box(265.8f, 344.8f, 311.8f, 381.8f), rightLowLabel = Box(316.0f, 303.2f, 362.0f, 340.1f),
            obstacles = listOf(
                Box(273.3f, 314.2f, 304.3f, 345.2f),
                foldMeteoTueDigits, Box(299.8f, 344.8f, 311.8f, 363.3f, isDegreeSign = true),
                Box(323.5f, 272.6f, 354.5f, 303.6f),
                foldMeteoWedDigits, Box(350.0f, 303.2f, 362.0f, 321.7f, isDegreeSign = true),
            ),
            dayLabelTop = 377.6f, canvasWidth = 560f, pxPerDp = 2.625f,
        ),
        "test",
    )

    @Test
    fun `pocket night label slides right off its own low's digits`() {
        val label = foldMeteoTueNight()
        val gapDp = (label.left - foldMeteoTueDigits.right) / 2.625f
        println("fold meteo 97%: centerX=${label.centerX} top=${label.top} left=${label.left} gapDp=$gapDp")
        assertTrue("still under Tue's °: left=${label.left}", label.left < 311.8f)
        assertEquals("1.5 dp clear of the 45", DailyRainLabels.NIGHT_POCKET_SIDE_GAP_DP, gapDp, 0.01f)
        assertTrue("well below Wed's 50: top=${label.top}", label.top - foldMeteoWedDigits.bottom >= 2.5f * 2.625f)
    }

    // Fold 4 inner, NWS, 2026-11-15 07:41 trace: Tue night ".45in" dropped into the pocket under Tue's
    // "42°" centred on the gap (289.4), then slid 1.9 dp right off the "42" box to (294.3, 355.1).
    // Its box starts with the decimal point, below the ink of "42" (user: "more tucked near 42°").
    private val foldNwsTueDigits = Box(238.8f, 334.0f, 272.8f, 370.9f, isLowDigits = true)
    private fun foldNwsTueNight(text: String) = DailyRainLabelLayout.layoutNight(
        DailyRainLabelLayout.Host { measured, _, _ ->
            if (measured == ".") DailyRainLabelLayout.TextSize(4.5f, 19.9f, 15.8f) else DailyRainLabelLayout.TextSize(35f, 19.9f, 15.8f)
        },
        DailyRainLabelLayout.NightInput(
            date = "2026-11-17", text = text, probability = 97, isPast = false, daysFromToday = 2,
            columnCenterX = 261.8f, gapCenterX = 289.4f, columnWidth = 55.2f,
            leftLowLabel = Box(238.8f, 334.0f, 284.8f, 370.9f), rightLowLabel = Box(294.0f, 290.6f, 340.0f, 327.5f),
            obstacles = listOf(
                Box(246.3f, 303.4f, 277.3f, 334.4f),
                foldNwsTueDigits, Box(272.8f, 334.0f, 284.8f, 352.5f, isDegreeSign = true),
                Box(301.5f, 259.9f, 332.5f, 290.9f),
                Box(294.0f, 290.6f, 328.0f, 327.5f, isLowDigits = true), Box(328.0f, 290.6f, 340.0f, 309.0f, isDegreeSign = true),
            ),
            dayLabelTop = 375.4f, canvasWidth = 552f, pxPerDp = 2.625f,
        ),
        "test",
    )

    @Test
    fun `night amount starting with a decimal point stays centred beside its own low`() {
        val amount = foldNwsTueNight(".45in")
        val chance = foldNwsTueNight("100%")
        println("fold nws .45in: centerX=${amount.centerX} top=${amount.top}; same box as 100%: centerX=${chance.centerX}")
        assertEquals("same row as before", 355.1f, amount.top, 0.3f)
        assertEquals("the dot is the gap: within 0.5 dp of the gap centre", 289.4f, amount.centerX, 0.5f * 2.625f)
        assertEquals("a label with ink at its left edge still slides 1.5 dp clear", 1.5f, (chance.left - foldNwsTueDigits.right) / 2.625f, 0.01f)
    }

    @Test
    fun `a trace night label is laid out a quarter smaller than an amount`() {
        val trace = foldNwsTueNight(com.weatherwidget.shared.util.PrecipTrace.LABEL)
        val amount = foldNwsTueNight("1in")
        println("trace scale=${trace.fontScale} amount scale=${amount.fontScale}")
        assertEquals(DailyRainLabels.TRACE_FONT_SCALE, trace.fontScale / amount.fontScale, 0.001f)
    }

    @Test
    fun `a leading decimal point level with the neighbour's ink is not a gap`() {
        val left = Box(60f, 40f, 100f, 80f, isLowDigits = true) // ink bottom 71.2
        fun slide(baselineY: Float) = DailyRainLabelPlanner.slideOffLeftNeighbor(
            centerX = 115f, top = 56f, width = 30f, height = 20f, rightDigits = null,
            obstacles = listOf(left), maxRight = 500f, density = 1f, leadingBlankPx = 4f, baselineY = baselineY,
        )
        assertEquals("dot beside the digits' ink: slides", 116.5f, slide(72f), 0.01f)
        assertEquals("dot below the digits' ink: 4 px of blank already clears", 115f, slide(76f), 0.01f)
    }

    @Test
    fun `side slide is capped, skips a label already clear, and never reaches under a near next low`() {
        val degree = Box(100f, 40f, 110f, 55f, isDegreeSign = true)
        val left = Box(60f, 40f, 100f, 80f, isLowDigits = true)
        fun slide(centerX: Float, right: Box?, extra: List<Box> = emptyList()) = DailyRainLabelPlanner.slideOffLeftNeighbor(
            centerX = centerX, top = 56f, width = 30f, height = 20f, rightDigits = right,
            obstacles = listOf(left, degree) + listOfNotNull(right) + extra, maxRight = 500f, density = 1f,
        )
        val farRight = Box(110f, 0f, 150f, 50f, isLowDigits = true)
        assertEquals("pressed by 3 px: capped at 2 dp", 112f, slide(110f, farRight), 0.01f)
        assertEquals("already 1.5 dp clear", 116.5f, slide(116.5f, farRight), 0.01f)
        val nearRight = Box(110f, 0f, 150f, 55f, isLowDigits = true)
        assertEquals("next low 1 dp above: stays", 115f, slide(115f, nearRight), 0.01f)
        assertEquals("slide would hit an icon: stays", 115f, slide(115f, null, listOf(Box(130.2f, 60f, 140f, 70f))), 0.01f)
    }

    @Test
    fun `night label pressed against its left day's icon slides right`() {
        // emulator-5554, Open-Meteo, 2026-11-15 06:58 trace: Tue night "97%" (shrunk) went 3 dp down and
        // was accepted 4.1 px into Tue's cloud icon box (slight by area); it read as touching the cloud.
        val tueIcon = Box(216.2f, 367.3f, 247.2f, 398.3f)
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(26.0f, 16.9f, 13.4f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-11-17", text = "97%", probability = 97, isPast = false, daysFromToday = 2,
                columnCenterX = 231.7f, gapCenterX = 256.1f, columnWidth = 48.8f,
                leftLowLabel = Box(208.7f, 398.0f, 254.7f, 434.9f), rightLowLabel = Box(257.5f, 332.7f, 303.5f, 369.6f),
                obstacles = listOf(
                    tueIcon,
                    Box(208.7f, 398.0f, 242.7f, 434.9f, isLowDigits = true), Box(242.7f, 398.0f, 254.7f, 416.5f, isDegreeSign = true),
                    Box(265.0f, 302.0f, 296.0f, 333.0f),
                    Box(257.5f, 332.7f, 291.5f, 369.6f, isLowDigits = true), Box(291.5f, 332.7f, 303.5f, 351.1f, isDegreeSign = true),
                ),
                dayLabelTop = 430.7f, canvasWidth = 540f, pxPerDp = 2.625f,
            ),
            "test",
        )
        println("emulator 97%: centerX=${label.centerX} top=${label.top} left=${label.left} width=${label.width} placement=${label.placement} scale=${label.fontScale}")
        assertTrue("moved right of the 256.1 it had: centerX=${label.centerX}", label.centerX > 256.1f + 1f)
        assertTrue("at most 2 dp further right", label.centerX <= 256.1f + 2f * 2.625f + 0.01f)
        assertTrue("well below Wed's 50: top=${label.top}", label.top - 369.6f >= 2.5f * 2.625f)
    }

    @Test
    fun `side slide does not undo the shrink that kept a label on its own day`() {
        // Same emulator trace at the device's measured sizes: full 30.0x19.8 ends half under Wed's "50",
        // the 0.85 retry (26.0x16.9) does not. The 2 dp side slide must not cancel that retry.
        val base = DailyRainLabels.rainLabelFontScale(false, 97, 2) * DailyRainLabels.NIGHT_SCALE
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, scale ->
                if (scale < base - 0.0001f) DailyRainLabelLayout.TextSize(26.0f, 16.9f, 13.4f)
                else DailyRainLabelLayout.TextSize(30.0f, 19.8f, 15.7f)
            },
            DailyRainLabelLayout.NightInput(
                date = "2026-11-17", text = "97%", probability = 97, isPast = false, daysFromToday = 2,
                columnCenterX = 231.7f, gapCenterX = 256.1f, columnWidth = 48.8f,
                leftLowLabel = Box(208.7f, 398.0f, 254.7f, 434.9f), rightLowLabel = Box(257.5f, 332.7f, 303.5f, 369.6f),
                obstacles = listOf(
                    Box(216.2f, 367.3f, 247.2f, 398.3f),
                    Box(208.7f, 398.0f, 242.7f, 434.9f, isLowDigits = true), Box(242.7f, 398.0f, 254.7f, 416.5f, isDegreeSign = true),
                    Box(265.0f, 302.0f, 296.0f, 333.0f),
                    Box(257.5f, 332.7f, 291.5f, 369.6f, isLowDigits = true), Box(291.5f, 332.7f, 303.5f, 351.1f, isDegreeSign = true),
                ),
                dayLabelTop = 430.7f, canvasWidth = 540f, pxPerDp = 2.625f,
            ),
            "test",
        )
        println("emulator 97% two sizes: centerX=${label.centerX} top=${label.top} width=${label.width} scale=${label.fontScale}")
        assertEquals("shrunk, as before the slide existed", 26.0f, label.width, 0.01f)
        assertEquals("then 2 dp right of 256.1", 256.1f + 2f * 2.625f, label.centerX, 0.01f)
    }

    @Test
    fun `tonight label over the blank top of the next low's digits eases only off the icon`() {
        // Fold 4, Open-Meteo, 2026-11-15 07:06 trace: Today→Mon "88%" at home (210.3, 329.8) eased
        // 6.2 dp left to 194.0, under Today's "51.4°", off the blank top 20 % of Mon's "46" box.
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(30.0f, 19.6f, 15.5f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-11-15", text = "88%", probability = 88, isPast = false, daysFromToday = 0,
                columnCenterX = 182.1f, gapCenterX = 210.3f, columnWidth = 56.4f,
                leftLowLabel = Box(146.6f, 292.0f, 217.6f, 328.9f), rightLowLabel = Box(215.6f, 340.5f, 261.6f, 377.4f),
                obstacles = listOf(
                    Box(166.6f, 261.3f, 197.6f, 292.3f),
                    Box(146.6f, 292.0f, 205.6f, 328.9f, isLowDigits = true), Box(205.6f, 292.0f, 217.6f, 310.4f, isDegreeSign = true),
                    Box(223.1f, 309.9f, 254.1f, 340.9f),
                    Box(215.6f, 340.5f, 249.6f, 377.4f, isLowDigits = true), Box(249.6f, 340.5f, 261.6f, 359.0f, isDegreeSign = true),
                ),
                dayLabelTop = 377.6f, canvasWidth = 560f, pxPerDp = 2.625f,
            ),
            "test",
        )
        println("fold tonight 88%: centerX=${label.centerX} top=${label.top}")
        val iconGap = DailyRainLabels.NIGHT_ICON_SIDE_GAP_DP * 2.625f
        assertEquals("1 dp off Mon's icon, not off the 46's blank top", 223.1f - iconGap - 15f, label.centerX, 0.1f)
        assertEquals(329.8f, label.top, 0.1f)
    }

    private fun lowDigits(left: Float, top: Float, right: Float, bottom: Float) =
        DailyRainLabelPlanner.lowLabelObstacles("47.9°", left, top, right, bottom, degreeWidth = 12f)

    @Test
    fun `night label kept off centre only by blank descent returns to the gap`() {
        // Tomorrow.io "40%" on the Fold, 09:52:46 trace: 2 dp left of centre, under the digits' ink.
        val p = 2.6f
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(26f, 16.6f, 13f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = "40%", probability = 40, isPast = false, daysFromToday = 0,
                columnCenterX = 167f, gapCenterX = 193.1f, columnWidth = 49f,
                leftLowLabel = Box(131.7f, 323.4f, 202.7f, 360.3f),
                rightLowLabel = Box(196.1f, 332.7f, 242.1f, 369.6f),
                obstacles = listOf(Box(151.7f, 292.8f, 182.7f, 323.8f)) + lowDigits(131.7f, 323.4f, 202.7f, 360.3f) +
                    Box(203.6f, 302f, 234.6f, 333f) + lowDigits(196.1f, 332.7f, 242.1f, 369.6f),
                dayLabelTop = 379.4f, canvasWidth = 480f, pxPerDp = p,
            ),
            "test",
        )
        assertEquals(193.1f, label.centerX, 0.01f)
        assertTrue("lower than the search left it: top=${label.top}", label.top > 357.7f)
        assertTrue("clear of the day names: bottom=${label.bottom}", label.bottom <= 379.4f)
    }

    @Test
    fun `night label level with the digits' ink stays where the search put it`() {
        val obstacles = lowDigits(242.2f, 332.2f, 288.2f, 369.1f)
        val done = DailyRainLabelPlanner.recenterOverBlankLineBox(
            homeCenterX = 248f, centerX = 236.9f, top = 340f, width = 19f, height = 12.3f,
            obstacles = obstacles, maxBottom = 379.4f, density = 2.625f,
        )
        assertEquals(236.9f, done.centerX, 0.01f)
        assertEquals(340f, done.top, 0.01f)
    }

    @Test
    fun `night label the search moved right returns when the sink slide is not needed`() {
        // Meteo "30%" on the Fold, 09:58 trace: the ° box drew a 3 dp slide on top of a 2 dp search move.
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(23f, 14.5f, 11f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = "30%", probability = 30, isPast = false, daysFromToday = 0,
                columnCenterX = 167f, gapCenterX = 193.1f, columnWidth = 49f,
                leftLowLabel = Box(131.7f, 329f, 202.7f, 365.9f),
                rightLowLabel = Box(196.1f, 319.7f, 242.1f, 356.6f),
                obstacles = listOf(Box(151.7f, 298.4f, 182.7f, 329.4f)) + lowDigits(131.7f, 329f, 202.7f, 365.9f) +
                    Box(203.6f, 289f, 234.6f, 320f) + lowDigits(196.1f, 319.7f, 242.1f, 356.6f),
                dayLabelTop = 379.4f, canvasWidth = 480f, pxPerDp = 2.625f,
            ),
            "test",
        )
        assertEquals(193.1f, label.centerX, 0.01f)
        assertTrue("clear of the day names: bottom=${label.bottom}", label.bottom <= 379.4f)
    }

    @Test
    fun `desktop night label moved off a blank degree box top returns to the gap`() {
        // Silurian "15%", 09:57:55 trace: 5 dp right, clearing only the empty top of the ° box.
        val p = 2.88f
        val left = DailyRainLabelPlanner.lowLabelObstacles("47.9°", 184.2f, 737f, 272.2f, 786f, degreeWidth = 16f)
        val right = DailyRainLabelPlanner.lowLabelObstacles("52°", 294f, 663.5f, 358f, 718.5f, degreeWidth = 18f)
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(36f, 25f, 19f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = "15%", probability = 15, isPast = false, daysFromToday = 0,
                columnCenterX = 234f, gapCenterX = 277.1f, columnWidth = 86f,
                leftLowLabel = Box(184.2f, 737f, 272.2f, 786f), rightLowLabel = Box(294f, 663.5f, 358f, 718.5f),
                obstacles = listOf(Box(202.1f, 684.9f, 254.3f, 737f)) + left + Box(299.9f, 611.3f, 352.1f, 663.5f) + right,
                dayLabelTop = 777.7f, canvasWidth = 1002f, pxPerDp = p,
            ),
            "test",
        )
        assertEquals(277.1f, label.centerX, 0.01f)
        assertEquals(723f, label.top, 0.5f)
    }

    @Test
    fun `night label rises a little to return when the degree ink is below it`() {
        // Desktop "15%" at the live 727.6 top: the ° ink stops it returning at that height.
        val p = 2.88f
        val left = DailyRainLabelPlanner.lowLabelObstacles("47.9°", 184.2f, 737f, 272.2f, 786f, degreeWidth = 16f)
        val done = DailyRainLabelPlanner.recenterOverBlankLineBox(
            homeCenterX = 277.1f, centerX = 291.6f, top = 727.6f, width = 36f, height = 25f,
            obstacles = left, maxBottom = 777.7f, density = p,
        )
        assertEquals(277.1f, done.centerX, 0.01f)
        assertTrue("risen at most 2 dp: top=${done.top}", done.top < 727.6f && done.top >= 727.6f - 2f * p)
    }

    @Test
    fun `night label on a low's digit ink drops as far as the day names allow`() {
        // Desktop "38%", 10:12 trace: its left edge sat on the 9 of "47.9", slight only by area.
        val low = DailyRainLabelPlanner.lowLabelObstacles("47.9°", 183.2f, 724.1f, 273.2f, 774.1f, degreeWidth = 16f)
        assertEquals(
            791.4f - 33f,
            DailyRainLabelPlanner.dropOffDigitInk(277.1f, 746.3f, 49f, 33f, low, 791.4f, 2.88f),
            0.01f,
        )
    }

    @Test
    fun `night label beside digits too tall to clear slides left instead of dropping`() {
        // Desktop Meteo "22%", 12:21 trace: dropped 5 dp down the side of the "52" and hugged it.
        val p = 2.88f
        val left = DailyRainLabelPlanner.lowLabelObstacles("55°", 467.4f, 580.7f, 532.4f, 636.7f, degreeWidth = 18f)
        val right = DailyRainLabelPlanner.lowLabelObstacles("52°", 554.3f, 645.1f, 619.3f, 701.1f, degreeWidth = 18f)
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(38f, 26f, 20f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-11-03", text = "22%", probability = 22, isPast = false, daysFromToday = 3,
                columnCenterX = 500f, gapCenterX = 543.3f, columnWidth = 86f,
                leftLowLabel = Box(467.4f, 580.7f, 532.4f, 636.7f), rightLowLabel = Box(554.3f, 645.1f, 619.3f, 701.1f),
                obstacles = listOf(Box(473.8f, 528.5f, 525.9f, 580.7f)) + left +
                    Box(560.7f, 592.9f, 612.9f, 645.1f) + right,
                dayLabelTop = 795.3f, canvasWidth = 1002f, pxPerDp = p,
            ),
            "test",
        )
        assertEquals("no futile drop", 644f, label.top, 1f)
        assertEquals("a dp clear of the digits", 554.3f - p, label.right, 0.01f)
    }

    @Test
    fun `night label on the left low's digits slides right when it cannot drop clear`() {
        // Desktop Meteo "39%", 12:27 trace: on the 9 of "47.9", the day names too close to drop off it.
        val p = 2.88f
        val left = DailyRainLabelPlanner.lowLabelObstacles("47.9°", 183.2f, 735.6f, 273.2f, 786.6f, degreeWidth = 16f)
        val right = DailyRainLabelPlanner.lowLabelObstacles("49°", 293f, 712.2f, 359f, 768.2f, degreeWidth = 18f)
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(49f, 34f, 26f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = "39%", probability = 39, isPast = false, daysFromToday = 0,
                columnCenterX = 234f, gapCenterX = 277.1f, columnWidth = 86f,
                leftLowLabel = Box(183.2f, 735.6f, 273.2f, 786.6f), rightLowLabel = Box(293f, 712.2f, 359f, 768.2f),
                obstacles = listOf(Box(202.1f, 683.4f, 254.3f, 735.6f)) + left +
                    Box(299.9f, 660.1f, 352.1f, 712.2f) + right,
                dayLabelTop = 798.2f, canvasWidth = 1002f, pxPerDp = p,
            ),
            "test",
        )
        assertEquals("a dp clear of the digits", 257.2f + p, label.left, 0.01f)
        assertEquals("air under the °", 761.1f + DailyRainLabels.NIGHT_DEGREE_GAP_DP * p, label.top, 0.05f)
    }

    private fun emuNight(text: String, w: Float, h: Float, gap: Float, left: Box, right: Box, leftText: String, rightText: String) =
        DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(w, h, h * 0.8f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = text, probability = 22, isPast = false, daysFromToday = 0,
                columnCenterX = gap - 33f, gapCenterX = gap, columnWidth = 66f,
                leftLowLabel = left, rightLowLabel = right,
                obstacles = DailyRainLabelPlanner.lowLabelObstacles(leftText, left.left, left.top, left.right, left.bottom, 10f) +
                    DailyRainLabelPlanner.lowLabelObstacles(rightText, right.left, right.top, right.right, right.bottom, 10f),
                dayLabelTop = 362.2f, canvasWidth = 480f, pxPerDp = 2.625f,
            ),
            "test",
        )

    @Test
    fun `night label wedged between lows never slides onto the left low and drops below it`() {
        // Emulator "4%", 12:33 trace: the side slide ran it from "53.2" into "49.3°".
        val label = emuNight("4%", 11f, 10f, 57f, Box(-2.5f, 326.6f, 59.5f, 357.2f), Box(54.5f, 334.4f, 116.5f, 365f), "49.3°", "53.2°")
        assertTrue("right of where the slide left it: ${label.centerX}", label.centerX > 50f)
        assertTrue("below the left digits' ink: top=${label.top}", label.top >= 350f)
        // The floor ease spends 0.5 dp of the label's blank descent.
        assertTrue("clear of the day names: bottom=${label.bottom}", label.bottom <= 362.2f + DailyRainLabels.NIGHT_FLOOR_DROP_DP * 2.75f)
    }

    @Test
    fun `night label beside a degree sign with little room goes down and right`() {
        // Emulator "22%", 12:33 trace: 3 px short of room for the full gap, so it stayed by "48.0°".
        val label = emuNight("22%", 23f, 14.3f, 181.6f, Box(117.1f, 331.7f, 182.1f, 365.7f), Box(192.7f, 316.1f, 234.7f, 350.1f), "48.0°", "49°")
        assertEquals("down to the day names", 362.2f - 14.3f, label.top, 0.01f)
        assertTrue("right of centre: ${label.centerX}", label.centerX > 181.6f)
    }

    @Test
    fun `night label short of the bottom limit by a hair still returns to the gap`() {
        // Fold Meteo "39%", 12:21 trace: centred needed a top 0.1 px past the day-name row.
        val p = 2.625f
        val done = DailyRainLabelPlanner.recenterOverBlankLineBox(
            homeCenterX = 147f, centerX = 141.8f, top = 360.4f, width = 26f, height = 16.5f,
            obstacles = DailyRainLabelPlanner.lowLabelObstacles("48°", 98.1f, 346.6f, 144.1f, 383.5f, degreeWidth = 12f) +
                DailyRainLabelPlanner.lowLabelObstacles("49°", 150f, 336.6f, 196f, 373.5f, degreeWidth = 12f),
            maxBottom = 379.4f, density = p,
        )
        assertEquals(147f, done.centerX, 0.01f)
        assertEquals(379.4f - 16.5f, done.top, 0.01f)
    }

    @Test
    fun `night label only skimming the side of a digit box stays`() {
        // Fold Silurian "15%": 1.8 px into the box's side padding, 0.7 dp.
        val low = DailyRainLabelPlanner.lowLabelObstacles("47.9°", 131.7f, 328.4f, 202.7f, 365.4f, degreeWidth = 12f)
        assertEquals(349.5f, DailyRainLabelPlanner.dropOffDigitInk(198.4f, 349.5f, 19f, 12.4f, low, 379.4f, 2.625f), 0.01f)
    }

    @Test
    fun `night label grazing a degree sign drops for air when there is room`() {
        // Silurian "15%" on the Fold, 10:05 trace: 2.3 px into the ° box of "47.9°".
        val p = 2.625f
        val low = DailyRainLabelPlanner.lowLabelObstacles("47.9°", 131.7f, 328.4f, 202.7f, 365.4f, degreeWidth = 12f)
        assertEquals(
            low[1].bottom + DailyRainLabels.NIGHT_DEGREE_GAP_DP * p,
            DailyRainLabelPlanner.dropClearOfDegree(198.4f, 344.6f, 19f, 12.4f, low, 379.4f, p),
            0.01f,
        )
    }

    @Test
    fun `night label under a degree sign takes the room there is and stays with something below`() {
        val p = 2.625f
        val low = DailyRainLabelPlanner.lowLabelObstacles("47.9°", 131.7f, 328.4f, 202.7f, 365.4f, degreeWidth = 12f)
        assertEquals(358f - 12.4f, DailyRainLabelPlanner.dropClearOfDegree(198.4f, 344.6f, 19f, 12.4f, low, 358f, p), 0.01f)
        val blocked = low + Box(190f, 350f, 215f, 372f)
        assertEquals(344.6f, DailyRainLabelPlanner.dropClearOfDegree(198.4f, 344.6f, 19f, 12.4f, blocked, 379.4f, p), 0.01f)
    }

    @Test
    fun `night label the search moved left does not ease further left`() {
        // Sun-Mon "10%" at the same height: eased to the 30 % limit, 5.3 dp left of centre.
        val p = 2.625f
        val eased = DailyRainLabelPlanner.easeOffNeighbors(
            homeCenterX = 242.1f, centerX = 236.85f, top = 327.6f, width = 19f, height = 12.3f,
            obstacles = listOf(
                Box(196.1f, 284.1f, 230.1f, 321f), Box(230.1f, 284.1f, 242.1f, 302.6f, isDegreeSign = true),
                Box(249.7f, 301.6f, 280.7f, 332.6f), Box(242.2f, 332.2f, 276.2f, 369.1f),
            ),
            rightLowLabel = Box(242.2f, 332.2f, 288.2f, 369.1f),
            maxShiftPx = 13.8f, maxBottom = 379.4f, minLeft = 10f, density = p, searchMovedLeft = true,
        )
        assertEquals(236.85f, eased.centerX, 0.01f)
        assertEquals(327.6f, eased.top, 0.01f)
    }

    @Test
    fun `fold history night label sits between the two lows`() {
        val label = foldNight(dayLabelTop = 413.6f)
        // The tuck may rise into the blank descent band under Tue's digits, never further.
        val tuck = DailyRainLabels.NIGHT_TUCK_OVERLAP_BASE_DP * 2.625f
        assertTrue("under Tue's low: top=${label.top}", label.top >= tueLow.bottom - tuck)
        assertTrue("left where it starts, in the gap: ${label.centerX}", kotlin.math.abs(label.centerX - 49.9f) < 0.01f)
        assertTrue("above Wed's low bottom: bottom=${label.bottom}", label.bottom <= wedLow.bottom)
        assertTrue("clear of the day names: bottom=${label.bottom}", label.bottom <= 413.6f)
    }

    @Test
    fun `night label sinks below the lower low when nothing above is clear`() {
        // The lower low spans the whole gap, so no sideways nudge clears it; below it there is room.
        val upperLow = Box(10f, 50f, 45f, 80f)
        val lowerLow = Box(0f, 84f, 120f, 110f)
        val label =
            DailyRainLabelLayout.layoutNight(
                foldHost,
                DailyRainLabelLayout.NightInput(
                    date = "d", text = "10%", probability = 10, isPast = true, daysFromToday = -2,
                    columnCenterX = 25f, gapCenterX = 50f, columnWidth = 50f,
                    leftLowLabel = upperLow, rightLowLabel = lowerLow,
                    obstacles = listOf(upperLow, lowerLow), dayLabelTop = 200f, canvasWidth = 400f, pxPerDp = 2.625f,
                ),
                "test",
            )
        assertTrue("top=${label.top} must clear the lower low's bottom ${lowerLow.bottom}", label.top >= lowerLow.bottom - DailyRainLabels.NIGHT_SLIGHT_OVERLAP_DP * 2.625f)
    }

    @Test
    fun `night label between two lows on the day-name row rises level with them`() {
        // The coldest days: both lows end at the day-name row, so there is no room under them.
        val leftLow = Box(0f, 80f, 40f, 100f)
        val rightLow = Box(110f, 80f, 150f, 100f)
        val label = DailyRainLabelLayout.layoutNight(
            foldHost,
            DailyRainLabelLayout.NightInput(
                date = "d", text = "10%", probability = 10, isPast = false, daysFromToday = 1,
                columnCenterX = 20f, gapCenterX = 75f, columnWidth = 110f,
                leftLowLabel = leftLow, rightLowLabel = rightLow,
                obstacles = listOf(leftLow, rightLow), dayLabelTop = 100f, canvasWidth = 400f, pxPerDp = 1f,
            ),
            "test",
        )
        assertTrue("clear of the day names: bottom=${label.bottom}", label.bottom <= 100f)
        assertTrue("between the lows: ${label.left}..${label.right}", label.left >= leftLow.right && label.right <= rightLow.left)
    }

    // Desktop Sat 10-31 → Sun, "30%" (autostart log 2026-10-30 09:08, scale 2.576): home touched Sat's °.
    private val satLow = Box(297.5f, 605.6f, 354.5f, 654.6f)
    private val sunLow = Box(384.4f, 561.7f, 441.4f, 610.7f)
    private val desktopObstacles = listOf(
        Box(299.9f, 553.5f, 352.1f, 605.6f), // Sat icon
        Box(297.5f, 605.6f, 338.5f, 654.6f), Box(338.5f, 605.6f, 354.5f, 630.1f, isDegreeSign = true), // "48" + °
        Box(386.9f, 509.6f, 439.0f, 561.7f), // Sun icon
        Box(384.4f, 561.7f, 425.4f, 610.7f), Box(425.4f, 561.7f, 441.4f, 586.2f, isDegreeSign = true), // "50" + °
    )

    private fun desktopNight(dayLabelTop: Float) = DailyRainLabelLayout.layoutNight(
        DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(38f, 26f, 20f) },
        DailyRainLabelLayout.NightInput(
            date = "2026-10-31", text = "30%", probability = 30, isPast = false, daysFromToday = 1,
            columnCenterX = 326f, gapCenterX = 369.5f, columnWidth = 87f,
            leftLowLabel = satLow, rightLowLabel = sunLow,
            obstacles = desktopObstacles, dayLabelTop = dayLabelTop, canvasWidth = 1000f, pxPerDp = 2.576f,
        ),
        "test",
    )

    @Test
    fun `night label on a degree sign moves down when there is lots of room`() {
        val label = desktopNight(dayLabelTop = 691.6f)
        assertEquals("stays in the gap", 369.5f, label.centerX, 0.01f)
        assertEquals("air under the °", 630.1f + DailyRainLabels.NIGHT_DEGREE_GAP_DP * 2.576f, label.top, 0.01f)
    }

    @Test
    fun `night label on a degree sign moves right when there is little room below`() {
        val label = desktopNight(dayLabelTop = 650f)
        assertTrue("right of the °: left=${label.left}", label.left >= 354.5f)
        assertTrue("clear of the day names: bottom=${label.bottom}", label.bottom <= 650f)
    }

    // Fold 4 inner screen, Sat 10-31 → Sun, "16%" (logcat 2026-10-30 09:26): touched Sun's "49".
    private val foldSatLow = Box(153f, 326.2f, 199f, 363.1f)
    private val foldSunLow = Box(217f, 337.1f, 263f, 374f)
    private val foldSatObstacles = listOf(
        Box(152.5f, 279.6f, 199.5f, 326.6f), // Sat icon
        Box(153f, 326.2f, 187f, 363.1f), Box(187f, 326.2f, 199f, 344.7f, isDegreeSign = true), // "50" + °
        Box(216.5f, 290.5f, 263.5f, 337.5f), // Sun icon
        Box(217f, 337.1f, 251f, 374f), Box(251f, 337.1f, 263f, 355.6f, isDegreeSign = true), // "49" + °
    )

    private fun foldSatNight(columnWidth: Float = 68f, obstacles: List<Box> = foldSatObstacles) =
        DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(19f, 12.4f, 10f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-31", text = "16%", probability = 16, isPast = false, daysFromToday = 1,
                columnCenterX = 174f, gapCenterX = 208f, columnWidth = columnWidth,
                leftLowLabel = foldSatLow, rightLowLabel = foldSunLow,
                obstacles = obstacles, dayLabelTop = 392.1f, canvasWidth = 560f, pxPerDp = 2.625f,
            ),
            "test",
        )

    @Test
    fun `night label hugging the next low eases left to even the gaps`() {
        val label = foldSatNight()
        assertEquals(202f, label.centerX, 0.01f)
        assertEquals(label.left - 187f, 217f - label.right, 0.01f)
    }

    @Test
    fun `night label with nothing level on its left eases only to a roomy gap`() {
        // Fold 4 narrow layout, Fri "2%" (logcat 09:31): Fri's low ends above the label, so the left
        // is open to the canvas edge. Evening the gaps slid it 6.6 dp, under Fri's low.
        val label = DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(7f, 7f, 6f) },
            DailyRainLabelLayout.NightInput(
                date = "2026-10-30", text = "2%", probability = 2, isPast = false, daysFromToday = 0,
                columnCenterX = 161f, gapCenterX = 193.1f, columnWidth = 57.6f,
                leftLowLabel = Box(131.7f, 291.5f, 202.7f, 328.5f), rightLowLabel = Box(196.1f, 302.3f, 242.1f, 339.2f),
                obstacles = listOf(
                    Box(151.7f, 260.9f, 182.7f, 291.9f),
                    Box(131.7f, 291.5f, 190.7f, 328.5f), Box(190.7f, 291.5f, 202.7f, 310f, isDegreeSign = true),
                    Box(203.6f, 271.6f, 234.6f, 302.6f),
                    Box(196.1f, 302.3f, 230.1f, 339.2f), Box(230.1f, 302.3f, 242.1f, 320.7f, isDegreeSign = true),
                ),
                dayLabelTop = 379.4f, canvasWidth = 560f, pxPerDp = 2.625f,
            ),
            "test",
        )
        assertEquals("roomy clear of Sat's digits", DailyRainLabels.NIGHT_ROOMY_SIDE_DP * 2.625f, 196.1f - label.right, 0.01f)
    }

    @Test
    fun `night label ease left stops at the maximum shift`() {
        val label = foldSatNight(columnWidth = 10f) // 30 % of a column = 3 px
        assertEquals(205f, label.centerX, 0.01f)
    }

    @Test
    fun `night label hugging the next low drops for room when its left is blocked`() {
        val blocked = foldSatObstacles + Box(180f, 350f, 197f, 366f)
        val slide = DailyRainLabels.NIGHT_EASE_DROP_SLIDE_DP * 2.625f
        assertEquals(208f - slide, foldSatNight(obstacles = blocked).centerX, 0.01f)
    }

    @Test
    fun `night label clear of the next low stays home`() {
        val noSunDigits = foldSatObstacles.filterNot { it.left == 217f }
        assertEquals(208f, foldSatNight(obstacles = noSunDigits).centerX, 0.01f)
    }

    // Generic foldable, Meteo (logcat 2026-10-30 09:36): Thu "49.3°", Today "55.1°", Sat "48°".
    private val emuThuObstacles = listOf(
        Box(67.5f, 275.8f, 103.5f, 311.8f), // Thu icon
        Box(54.5f, 314f, 106.5f, 344.5f), Box(106.5f, 314f, 116.5f, 329.3f, isDegreeSign = true), // "49.3" + °
    )
    private val emuTodayObstacles = listOf(
        Box(131.6f, 285.1f, 167.6f, 321.1f), // Today icon
        Box(117.1f, 320.6f, 172.1f, 354.5f), Box(172.1f, 320.6f, 182.1f, 337.5f, isDegreeSign = true), // "55.1" + °
    )
    private val emuSatObstacles = listOf(
        Box(195.7f, 275.8f, 231.7f, 311.8f), // Sat icon
        Box(192.7f, 311.3f, 224.7f, 345.2f), Box(224.7f, 311.3f, 234.7f, 328.3f, isDegreeSign = true), // "48" + °
    )

    private fun emuNight(left: List<Box>, right: List<Box>, gapCenterX: Float, width: Float, height: Float) =
        DailyRainLabelLayout.layoutNight(
            DailyRainLabelLayout.Host { _, _, _ -> DailyRainLabelLayout.TextSize(width, height, height * 0.8f) },
            DailyRainLabelLayout.NightInput(
                date = "d", text = "4%", probability = 4, isPast = true, daysFromToday = -1,
                columnCenterX = gapCenterX - 32f, gapCenterX = gapCenterX, columnWidth = 64f,
                leftLowLabel = Box(left[1].left, left[1].top, left[2].right, left[1].bottom),
                rightLowLabel = Box(right[1].left, right[1].top, right[2].right, right[1].bottom),
                obstacles = left + right, dayLabelTop = 362.2f, canvasWidth = 560f, pxPerDp = 2.625f,
            ),
            "test",
        )

    @Test
    fun `night label wedged between two lows drops and stays in between them`() {
        val label = emuNight(emuThuObstacles, emuTodayObstacles, gapCenterX = 117.5f, width = 11f, height = 10f)
        assertEquals("dropped 4 dp from the 1 dp left search result's row", 331.4f + 4f * 2.625f, label.top, 0.1f)
        assertEquals("1.5 dp left of the middle", 117.5f - 1.5f * 2.625f, label.centerX, 0.1f)
        assertTrue("above the lower low's bottom: bottom=${label.bottom}", label.bottom <= 354.5f)
    }

    @Test
    fun `night label under a degree sign eases right when something sits on its right`() {
        val label = emuNight(emuTodayObstacles, emuSatObstacles, gapCenterX = 181.6f, width = 7f, height = 7f)
        assertEquals(187.4f, label.centerX, 0.05f)
        assertEquals("even gaps", label.left - 182.1f, 192.7f - label.right, 0.05f)
    }

    @Test
    fun `night label at the canvas edge shrinks, then centres on its column`() {
        fun place(gapCenterX: Float) = DailyRainLabelLayout.layoutNight(
            host,
            DailyRainLabelLayout.NightInput(
                date = "d", text = "100%", probability = 100, isPast = false, daysFromToday = 0,
                columnCenterX = 180f, gapCenterX = gapCenterX, columnWidth = 40f,
                leftLowLabel = Box(160f, 50f, 200f, 70f), rightLowLabel = null,
                obstacles = emptyList(), dayLabelTop = 200f, canvasWidth = 220f, pxPerDp = 1f,
            ),
            "test",
        )
        val edge = DailyRainLabels.RAIN_LABEL_EDGE_MARGIN_DP
        val fullWidth = 40f * DailyRainLabels.rainLabelFontScale(false, 100, 0) * DailyRainLabels.NIGHT_SCALE
        val reducedWidth = fullWidth * DailyRainLabelLayout.NIGHT_REDUCED_SCALE
        assertEquals("NIGHT_SHIFTED_LEFT", place(gapCenterX = 110f).placement)
        assertEquals("NIGHT_SHIFTED_SCALED", place(gapCenterX = 220f - edge - reducedWidth / 2f - 0.5f).placement)
        val centred = place(gapCenterX = 220f)
        assertEquals("NIGHT_CENTERED", centred.placement)
        assertTrue(centred.right <= 220f - edge)
    }

    private fun day(
        highLabelTop: Float?,
        ceiling: Float = 0f,
        peakText: String? = null,
        rawHighLabelTop: Float? = highLabelTop,
        probability: Int = 40,
        daysFromToday: Int = 0,
        peakCeiling: Float = ceiling,
    ) = DailyRainLabelLayout.layoutDay(
        host,
        DailyRainLabelLayout.DayInput(
            date = "d", rainText = "$probability%", peakText = peakText, probability = probability, isPast = false,
            daysFromToday = daysFromToday, centerX = 50f, highLabelTop = highLabelTop, snapshotBarTop = null,
            rawHighLabelTop = rawHighLabelTop, ceiling = ceiling, peakCeiling = peakCeiling, pxPerDp = 1f,
        ),
        "test",
    )

    @Test
    fun `day label tucks onto the high label`() {
        val labels = day(highLabelTop = 100f)!!
        // bottom = high top − RAIN_HIGH_TEMP_GAP_DP (−3 dp → 3 px into the high label).
        assertEquals(100f - DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP, labels.rain.bottom, 0.01f)
        assertEquals(labels.rain.top, labels.rawTop, 0.01f)
    }

    @Test
    fun `day label clamps to the ceiling and reports its unclamped top`() {
        val labels = day(highLabelTop = 10f, ceiling = 5f)!!
        assertEquals(5f, labels.rain.top, 0.01f)
        assertTrue("rawTop=${labels.rawTop}", labels.rawTop < 5f)
    }

    @Test
    fun `raw top follows the unclamped high label`() {
        val labels = day(highLabelTop = 20f, rawHighLabelTop = -30f, ceiling = 0f)!!
        assertEquals(-30f - DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP - 14f, labels.rawTop, 0.01f)
    }

    @Test
    fun `peak sits on the chance label and is dropped at the ceiling`() {
        val fits = day(highLabelTop = 100f, peakText = "1pm")!!
        val peak = checkNotNull(fits.peak)
        assertEquals(fits.rain.top - DailyRainLabels.RAIN_PEAK_GAP_DP, peak.bottom, 0.01f)
        assertTrue("rawTop includes the peak", fits.rawTop <= peak.top)

        val tooHigh = day(highLabelTop = 30f, peakText = "1pm", ceiling = 10f)!!
        assertNull(tooHigh.peak)
        assertTrue("the chance label stays put", tooHigh.rain.top >= 10f)
    }

    @Test
    fun `peak may overlap the header up to the drawable top`() {
        // Fold 4, 2026-11-04 (WAPI, Thu 11%): the graph top was solved so the peak ended exactly on
        // the header ceiling, and the strict check dropped it on a float sliver. The peak may now
        // rise into the header; only the drawable top (Android bitmap top) drops it.
        val ceiling = 20f
        val atTie = checkNotNull(day(highLabelTop = 40f, peakText = "7pm", ceiling = ceiling, peakCeiling = 0f))
        val peak = checkNotNull(atTie.peak) { "peak dropped although it is inside the bitmap" }
        println("peak over header: rainTop=${atTie.rain.top} peakTop=${peak.top} headerCeiling=$ceiling")
        assertTrue("the peak reaches into the header", peak.top < ceiling)
        assertTrue("the chance label still keeps to the header ceiling", atTie.rain.top >= ceiling)

        // The % clamps to a ceiling of 5, so the peak (14 tall, tucked 2) would start at −7.
        val offBitmap = checkNotNull(day(highLabelTop = 10f, peakText = "7pm", ceiling = 5f, peakCeiling = 0f))
        assertNull("a peak above the bitmap top is still dropped", offBitmap.peak)
    }

    @Test
    fun `peak hour font is at least 15 percent smaller than a chance of 30 or less`() {
        // User, 2026-11-04: with the threshold lowered to > 10 %, the hour must not compete with a small %.
        for (probability in 11..30) for (daysFromToday in 0..7) {
            val labels = checkNotNull(day(highLabelTop = 200f, peakText = "1pm", probability = probability, daysFromToday = daysFromToday))
            val peak = checkNotNull(labels.peak) { "peak dropped at $probability% day+$daysFromToday" }
            val ratio = peak.fontScale / labels.rain.fontScale
            println("peak font ratio: $probability% day+$daysFromToday rain=${labels.rain.fontScale} peak=${peak.fontScale} ratio=$ratio")
            assertTrue("$probability% day+$daysFromToday ratio=$ratio", ratio <= 0.85f + 1e-4f)
        }
    }

    @Test
    fun `no high label means no day label`() {
        assertNull(day(highLabelTop = null))
    }
}
