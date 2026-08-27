package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CloudVerticalKindTest {

    @Test
    fun `database codes are explicit and stable`() {
        assertEquals(0, CloudVerticalKind.NONE.dbCode)
        assertEquals(10, CloudVerticalKind.PROVIDER_BANDS.dbCode)
        assertEquals(20, CloudVerticalKind.CUMULATIVE_LAYERS.dbCode)
        assertEquals(30, CloudVerticalKind.TOTAL_ENVELOPE.dbCode)
        assertEquals(127, CloudVerticalKind.OTHER.dbCode)
    }

    @Test
    fun `known codes round trip and unknown codes become other`() {
        CloudVerticalKind.entries.forEach { kind ->
            assertEquals(kind, CloudVerticalKind.fromDbCode(kind.dbCode))
        }
        assertEquals(CloudVerticalKind.OTHER, CloudVerticalKind.fromDbCode(99))
        assertEquals(CloudVerticalKind.OTHER, CloudVerticalKind.fromDbCode(Int.MAX_VALUE))
    }
}
