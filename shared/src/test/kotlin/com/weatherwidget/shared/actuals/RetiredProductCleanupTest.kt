package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.RetiredActualsProducts
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class RetiredProductCleanupTest {
    private val calls = mutableListOf<String>()
    private val product = RetiredActualsProducts.TOMORROW_IO

    private fun run(coverage: Int, retired: Int, daily: Int = 1) = RetiredProductCleanup.retireIfCovered(
        product = product,
        countObservations = { calls += "coverage"; coverage },
        deleteObservations = { calls += "retired"; retired },
        deleteDailyRows = { calls += "daily"; daily },
    )

    @Test
    fun `nothing is retired before the replacement product has rows at the site`() {
        assertNull(run(coverage = 0, retired = 3))
        assertEquals(listOf("coverage"), calls)
    }

    @Test
    fun `daily rows are never deleted when no old observations were retired`() {
        // 2026-09-28 fold: `retiredObservations=0 dailyRows=1` deleted Warsaw's fresh yesterday.
        assertNull(run(coverage = 277, retired = 0))
        assertEquals(listOf("coverage", "retired"), calls)
    }

    @Test
    fun `daily rows go only alongside retired observations`() {
        assertEquals(RetiredProductCleanup.Outcome("TOMORROW_IO", 277, 3, 1), run(coverage = 277, retired = 3))
        assertEquals(listOf("coverage", "retired", "daily"), calls)
    }

    @Test
    fun `retired rows are exactly the source's rows outside its current product`() {
        val coverage = RetiredActualsProducts.coverageWhere(product)
        val retired = RetiredActualsProducts.retiredObservationsWhere(product)
        assertTrue(coverage, coverage.startsWith("api = 'TOMORROW_IO' AND (${product.currentProductWhere})"))
        assertTrue(retired, retired.startsWith("api = 'TOMORROW_IO' AND NOT (${product.currentProductWhere})"))
        assertTrue(RetiredActualsProducts.computedDailyRowsWhere(product).contains("computedHighTemp IS NOT NULL"))
    }

    @Test
    fun `only registered sources have a migration`() {
        assertEquals(listOf(product), RetiredActualsProducts.forApi("TOMORROW_IO"))
        assertTrue(RetiredActualsProducts.forApi("OPEN_METEO").isEmpty())
    }
}
