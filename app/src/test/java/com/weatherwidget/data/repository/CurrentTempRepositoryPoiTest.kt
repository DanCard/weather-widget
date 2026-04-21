package com.weatherwidget.data.repository

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CurrentTempRepositoryPoiTest {

    @Test
    fun `appendHistoricalPoi stores and rotates unique locations`() {
        val result = CurrentTempRepository.appendHistoricalPoi("", 37.422, -122.084, "Mountain View")

        assertEquals("Mountain View|37.422|-122.084", result)
    }

    @Test
    fun `parseHistoricalPois applies user aliases correctly`() {
        val poiString = "Mountain View|37.422|-122.084"
        val result = CurrentTempRepository.parseHistoricalPois(poiString)

        assertEquals(1, result.size)
        assertEquals("Mountain View", result[0].third)
        assertEquals(37.422, result[0].first, 0.001)
    }

    @Test
    fun `appendHistoricalPoi removes duplicate location before adding`() {
        val existing = "Sunnyvale|37.368|-122.036;Mountain View|37.422|-122.084"
        val result = CurrentTempRepository.appendHistoricalPoi(existing, 37.422, -122.084, "Mt View Updated")

        assertEquals("Mt View Updated|37.422|-122.084;Sunnyvale|37.368|-122.036", result)
    }

    @Test
    fun `appendHistoricalPoi limits to three entries`() {
        val existing = "Loc3|33.0|-133.0;Loc2|32.0|-132.0;Loc1|31.0|-131.0"
        val result = CurrentTempRepository.appendHistoricalPoi(existing, 37.422, -122.084, "New Loc")

        assertEquals(3, result.split(";").size)
        assertTrue(result.startsWith("New Loc|37.422|-122.084"))
    }

    @Test
    fun `parseHistoricalPois handles empty string`() {
        val result = CurrentTempRepository.parseHistoricalPois("")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseHistoricalPois handles multiple entries`() {
        val poiString = "Mountain View|37.422|-122.084;Sunnyvale|37.368|-122.036"
        val result = CurrentTempRepository.parseHistoricalPois(poiString)

        assertEquals(2, result.size)
        assertEquals("Mountain View", result[0].third)
        assertEquals("Sunnyvale", result[1].third)
    }
}