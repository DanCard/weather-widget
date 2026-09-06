package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PersonalStationThinningTest {

    private data class Row(val station: String, val type: String?, val ts: Long)

    private fun thin(rows: List<Row>) = PersonalStationThinning.thin(
        rows = rows,
        stationOf = Row::station,
        stationTypeOf = Row::type,
        timestampOf = Row::ts,
    )

    private val min = 60_000L
    private val base = 1_788_566_400_000L

    @Test
    fun `official stations are never thinned however dense`() {
        // KSJC reports every 4.6 min and is OFFICIAL, full-weight and sky-reporting. An
        // interval-based rule would thin exactly the station that must not be.
        val rows = (0..11).map { Row("KSJC", "OFFICIAL", base + it * 5 * min) }
        assertEquals(rows, thin(rows))
    }

    @Test
    fun `a personal station reporting every five minutes is halved`() {
        val rows = (0..11).map { Row("G4110", "PERSONAL", base + it * 5 * min) }
        val kept = thin(rows)
        // Buckets are 10 min, so one row per bucket plus the unconditional newest.
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L, 50L, 55L), kept.map { (it.ts - base) / min })
    }

    @Test
    fun `a personal station already at ten minutes is untouched`() {
        val rows = (0..5).map { Row("496PG", "PERSONAL", base + it * 10 * min) }
        assertEquals(rows, thin(rows))
    }

    @Test
    fun `a personal station at fifteen minutes is untouched`() {
        val rows = (0..3).map { Row("E0597", "PERSONAL", base + it * 15 * min) }
        assertEquals(rows, thin(rows))
    }

    @Test
    fun `each personal station keeps its newest row whatever the spacing`() {
        // Latest-reading staleness drives DOMINANT_STATION, readingAgeMin and the backfill's
        // latest_gap_min gate, so the newest row must survive even when it shares a bucket.
        val rows = listOf(
            Row("F4751", "PERSONAL", base),
            Row("F4751", "PERSONAL", base + 2 * min),
            Row("F4751", "PERSONAL", base + 4 * min),
        )
        val kept = thin(rows)
        assertTrue("newest row must survive", kept.any { it.ts == base + 4 * min })
        assertEquals(listOf(0L, 4L), kept.map { (it.ts - base) / min })
    }

    @Test
    fun `stations are bucketed independently`() {
        val rows = listOf(
            Row("A", "PERSONAL", base),
            Row("B", "PERSONAL", base + min),
            Row("A", "PERSONAL", base + 2 * min),
            Row("B", "PERSONAL", base + 3 * min),
        )
        val kept = thin(rows)
        assertEquals(setOf("A", "B"), kept.map { it.station }.toSet())
        // Each station keeps its bucket-earliest and its newest; here those are its only two rows.
        assertEquals(4, kept.size)
    }

    @Test
    fun `overlapping fetches converge on the same kept set`() {
        // The reason bucketing is absolute rather than walked forward from the batch's first row:
        // fetch windows overlap, and a batch-relative rule would keep a different subset each time
        // the window shifted, growing the table instead of shrinking it.
        val full = (0..23).map { Row("G6550", "PERSONAL", base + it * 5 * min) }
        val shifted = full.drop(3)

        val fromFull = thin(full).map { it.ts }.toSet()
        val fromShifted = thin(shifted).map { it.ts }.toSet()

        // Every row the shifted window keeps, other than rows it could not see and its own newest,
        // is one the full window also keeps.
        val extra = fromShifted - fromFull
        assertTrue("at most one boundary row may differ, got $extra", extra.size <= 1)
    }

    @Test
    fun `unknown or missing station types are treated as official`() {
        val rows = (0..5).map { Row("mystery", null, base + it * min) }
        assertEquals(rows, thin(rows))
    }

    @Test
    fun `input order does not change what is kept`() {
        val rows = (0..11).map { Row("E7138", "PERSONAL", base + it * 5 * min) }
        assertEquals(thin(rows).toSet(), thin(rows.reversed()).toSet())
    }

    @Test
    fun `empty input is returned unchanged`() {
        assertEquals(emptyList<Row>(), thin(emptyList()))
    }

    @Test
    fun `a mixed batch thins only the personal rows`() {
        val rows = (0..5).flatMap {
            listOf(
                Row("KSJC", "OFFICIAL", base + it * 5 * min),
                Row("G4110", "PERSONAL", base + it * 5 * min),
            )
        }
        val kept = thin(rows)
        assertEquals(6, kept.count { it.station == "KSJC" })
        assertTrue(kept.count { it.station == "G4110" } < 6)
    }
}
