package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HourlyFooterLayoutPlannerTest {
    @Test
    fun `date label center clamps inward when there is room`() {
        val center = HourlyFooterLayoutPlanner.placeDateLabelCenter(
            centerX = 5f,
            leftExtent = 30f,
            rightExtent = 30f,
            widthPx = 800,
            previousRightPx = Float.NEGATIVE_INFINITY,
            minGapPx = 6f,
        )
        assertEquals(30f, center!!, 0.001f)
    }

    @Test
    fun `date label center drops overlap and oversized labels`() {
        assertNull(
            HourlyFooterLayoutPlanner.placeDateLabelCenter(
                centerX = 110f,
                leftExtent = 30f,
                rightExtent = 30f,
                widthPx = 800,
                previousRightPx = 100f,
                minGapPx = 6f,
            ),
        )
        assertNull(
            HourlyFooterLayoutPlanner.placeDateLabelCenter(
                centerX = 100f,
                leftExtent = 120f,
                rightExtent = 120f,
                widthPx = 200,
                previousRightPx = Float.NEGATIVE_INFINITY,
                minGapPx = 6f,
            ),
        )
    }

    @Test
    fun `oversized icon group retries without icon instead of throwing`() {
        val plan = plan(
            items =
                listOf(
                    input(index = 0, x = 20f, hasIcon = true),
                    input(index = 1, x = 60f),
                ),
            widthPx = 80,
            iconSizePx = 70f,
            textWidth = 20f,
        )

        assertFalse(plan.drawsIcons)
        assertEquals(2, plan.placements.size)
        assertTrue(plan.placements.all { it.iconBounds == null })
    }

    @Test
    fun `text wider than canvas is dropped without throwing`() {
        val plan = plan(
            items = listOf(input(index = 0, x = 20f)),
            widthPx = 40,
            textWidth = 60f,
        )

        assertTrue(plan.placements.isEmpty())
    }

    @Test
    fun `all overlapping candidates use sparse fallback and drop collisions`() {
        val items = (0 until 12).map { input(index = it, x = it * 3f) }
        val plan = plan(
            items = items,
            widthPx = 120,
            minSpacingPx = 1f,
            textWidth = 45f,
        )

        assertTrue(plan.usedFallback)
        assertFalse(plan.drawsIcons)
        assertEquals(2.2f, plan.spacingPx, 0.001f)
        plan.placements.zipWithNext().forEach { (left, right) ->
            assertTrue(left.textBounds.right + 3f <= right.textBounds.left)
        }
    }

    @Test
    fun `missing icon callback produces a text-only plan`() {
        val plan = plan(
            items = listOf(input(index = 0, x = 40f, hasIcon = true)),
            iconsAvailable = false,
        )

        assertFalse(plan.drawsIcons)
        assertNull(plan.placements.single().iconBounds)
    }

    private fun input(
        index: Int,
        x: Float,
        hasIcon: Boolean = false,
    ) = HourlyFooterLayoutPlanner.LabelInput(
        itemIndex = index,
        centerX = x,
        text = "${index}p",
        showLabel = true,
        hasIcon = hasIcon,
        isDateLabel = false,
    )

    private fun plan(
        items: List<HourlyFooterLayoutPlanner.LabelInput>,
        widthPx: Int = 200,
        minSpacingPx: Float = 20f,
        iconSizePx: Float = 15f,
        textWidth: Float = 20f,
        iconsAvailable: Boolean = true,
    ): HourlyFooterLayoutPlanner.Plan =
        HourlyFooterLayoutPlanner.plan(
            items = items,
            widthPx = widthPx,
            heightPx = 100,
            minSpacingPx = minSpacingPx,
            textAscent = -10f,
            textDescent = 2f,
            measureText = { textWidth },
            iconSizePx = iconSizePx,
            iconTextGapPx = 2f,
            footerBottomInsetPx = 1f,
            minLabelGapPx = 3f,
            dateLabelGapPx = 6f,
            iconsAvailable = iconsAvailable,
        )
}
