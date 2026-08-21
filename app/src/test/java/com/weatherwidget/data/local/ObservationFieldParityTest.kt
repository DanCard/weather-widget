package com.weatherwidget.data.local

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards the hand-written `ObservationReading <-> ObservationEntity` conversions
 * (`ObservationEntity.toReading` and `NwsObservationSource.toEntity`). They are field-by-field
 * copies, and a new column added to one side but not the other fails by *omission* — which no
 * compiler catches. The `isMetar` column shipped exactly this way once (caught only by a
 * value-asserting Room round-trip test). This pins the field sets so the next drift is a red unit
 * test.
 */
@Category(ShortDuration::class)
class ObservationFieldParityTest {

    @Test
    fun `reading and room entity carry the same fields`() {
        val reading = dataFields(ObservationReading::class.java)
        val entity = dataFields(ObservationEntity::class.java)

        assertTrue("reflection found no fields — this test would be vacuous", reading.isNotEmpty())
        assertTrue("reflection found no fields — this test would be vacuous", entity.isNotEmpty())

        assertEquals(
            "field sets drifted (add to both, or add a conversion)",
            reading.keys,
            entity.keys,
        )
        for ((name, type) in reading) {
            assertEquals("field '$name' type drifted", type, entity[name])
        }
    }

    /** Name -> backing-field type. `declaredFields` exposes boxed-vs-primitive, i.e. nullability. */
    private fun dataFields(type: Class<*>): Map<String, Class<*>> =
        type.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.type }
}
