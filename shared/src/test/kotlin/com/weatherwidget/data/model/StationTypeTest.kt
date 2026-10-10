package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.sql.DriverManager

@Category(ShortDuration::class)
class StationTypeTest {

    /**
     * The codes are what both databases store. Pinned literally so reordering or inserting an entry
     * fails here instead of silently reinterpreting every stored row.
     */
    @Test
    fun `db codes are pinned`() {
        assertEquals(
            mapOf(
                StationType.UNKNOWN to 0,
                StationType.OFFICIAL to 1,
                StationType.PERSONAL to 2,
                StationType.RAWS to 3,
                StationType.BLENDED to 4,
            ),
            StationType.entries.associateWith { it.dbCode },
        )
    }

    @Test
    fun `unknown code or name reads as UNKNOWN rather than failing`() {
        assertEquals(StationType.UNKNOWN, StationType.fromDbCode(99))
        assertEquals(StationType.UNKNOWN, StationType.fromName("VIRTUAL"))
        assertEquals(StationType.UNKNOWN, StationType.fromName(null))
        StationType.entries.forEach {
            assertEquals(it, StationType.fromDbCode(it.dbCode))
            assertEquals(it, StationType.fromName(it.name))
        }
    }

    @Test
    fun `RAWS is discounted like personal stations`() {
        assertTrue(StationType.PERSONAL.isDiscounted)
        assertTrue(StationType.RAWS.isDiscounted)
        assertFalse(StationType.OFFICIAL.isDiscounted)
        assertFalse(StationType.UNKNOWN.isDiscounted)
        assertFalse(StationType.BLENDED.isDiscounted)
    }

    @Test
    fun `blend tab letters`() {
        assertEquals(listOf("?", "O", "P", "F", "B"), StationType.entries.map { it.label })
    }

    /** The migrations' CASE, run in real SQLite: every legacy spelling lands on its code. */
    @Test
    fun `legacy name CASE maps every stored spelling`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t (stationType TEXT)")
                listOf("OFFICIAL", "PERSONAL", "RAWS", "BLENDED", "VIRTUAL", "UNKNOWN", "junk")
                    .forEach { st.execute("INSERT INTO t VALUES ('$it')") }
                val mapped = st.executeQuery(
                    "SELECT stationType, ${StationType.SQL_CODE_FROM_LEGACY_NAME} FROM t",
                ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
                assertEquals(
                    mapOf(
                        "OFFICIAL" to 1, "PERSONAL" to 2, "RAWS" to 3, "BLENDED" to 4,
                        "VIRTUAL" to 4, "UNKNOWN" to 0, "junk" to 0,
                    ),
                    mapped,
                )
            }
        }
    }
}
