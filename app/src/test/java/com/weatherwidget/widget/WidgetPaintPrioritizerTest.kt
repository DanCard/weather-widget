package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.MediumDuration
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
class WidgetPaintPrioritizerTest : RobolectricTest() {

    private lateinit var context: Context
    private val appWidgetManager: AppWidgetManager = mockk()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `empty or single widget returns unchanged`() {
        val empty = intArrayOf()
        assertArrayEquals(empty, WidgetPaintPrioritizer.prioritize(context, empty, 1, appWidgetManager))

        val single = intArrayOf(42)
        assertArrayEquals(single, WidgetPaintPrioritizer.prioritize(context, single, 42, appWidgetManager))
    }

    @Test
    fun `lastInteractedId is placed at index 0`() {
        every { appWidgetManager.getAppWidgetOptions(any()) } returns Bundle()

        val widgetIds = intArrayOf(10, 20, 30, 40)
        val prioritized = WidgetPaintPrioritizer.prioritize(
            context = context,
            appWidgetIds = widgetIds,
            lastInteractedId = 30,
            appWidgetManager = appWidgetManager,
        )

        assertEquals(30, prioritized[0])
        assertEquals(4, prioritized.size)
        assertEquals(setOf(10, 20, 30, 40), prioritized.toSet())
    }

    @Test
    fun `unknown lastInteractedId preserves stable remaining order`() {
        every { appWidgetManager.getAppWidgetOptions(any()) } returns Bundle()

        val widgetIds = intArrayOf(10, 20, 30)
        val prioritized = WidgetPaintPrioritizer.prioritize(
            context = context,
            appWidgetIds = widgetIds,
            lastInteractedId = 999, // not in the list
            appWidgetManager = appWidgetManager,
        )

        assertArrayEquals(widgetIds, prioritized)
    }

    @Test
    fun `foldable large screen prioritizes wide widgets over narrow widgets`() {
        context.resources.configuration.smallestScreenWidthDp = 700

        val narrowOptions = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
        }
        val wideOptions = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 500)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 500)
        }

        every { appWidgetManager.getAppWidgetOptions(10) } returns narrowOptions
        every { appWidgetManager.getAppWidgetOptions(20) } returns wideOptions
        every { appWidgetManager.getAppWidgetOptions(30) } returns narrowOptions
        every { appWidgetManager.getAppWidgetOptions(40) } returns wideOptions

        val prioritized = WidgetPaintPrioritizer.prioritize(
            context = context,
            appWidgetIds = intArrayOf(10, 20, 30, 40),
            lastInteractedId = null,
            appWidgetManager = appWidgetManager,
        )

        // Wide widgets (20, 40) should be prioritized ahead of narrow (10, 30)
        assertEquals(20, prioritized[0])
        assertEquals(40, prioritized[1])
        assertEquals(10, prioritized[2])
        assertEquals(30, prioritized[3])
    }
}
