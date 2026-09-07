package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TodayColumnOverlayBlocksTest {

    @Test
    fun `all fields present yields delta block and dominant block`() {
        val blocks = TodayColumnOverlayBlocks.build("+0.4", "fcst", "62.6°")

        assertEquals(2, blocks.size)
        assertEquals(TodayColumnOverlayBlocks.KEY_DELTA, blocks[0].key)
        assertEquals(
            listOf(TodayColumnOverlayBlocks.Row("+0.4", "fcst")),
            blocks[0].rows,
        )
        assertEquals(TodayColumnOverlayBlocks.KEY_DOMINANT_TEMP_AGE, blocks[1].key)
        assertEquals(
            listOf(TodayColumnOverlayBlocks.Row("62.6°")),
            blocks[1].rows,
        )
    }

    @Test
    fun `temperature renders alone when toggled on without delta`() {
        val blocks = TodayColumnOverlayBlocks.build(null, null, "62.6°")

        assertEquals(1, blocks.size)
        assertEquals(
            listOf(TodayColumnOverlayBlocks.Row("62.6°")),
            blocks[0].rows,
        )
    }

    @Test
    fun `delta renders alone when dominant temperature is off`() {
        val blocks = TodayColumnOverlayBlocks.build("+0.4", "fcst", null)

        assertEquals(1, blocks.size)
        assertEquals(TodayColumnOverlayBlocks.KEY_DELTA, blocks[0].key)
    }

    @Test
    fun `all fields null yields no blocks`() {
        assertTrue(TodayColumnOverlayBlocks.build(null, null, null).isEmpty())
    }

    @Test
    fun `blank strings are treated as absent`() {
        val blocks = TodayColumnOverlayBlocks.build(" ", "", "")

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `blank caption is dropped from the delta row`() {
        val blocks = TodayColumnOverlayBlocks.build("+0.4", " ", null)

        assertEquals(1, blocks.size)
        assertNull(blocks[0].rows.single().caption)
    }
}
