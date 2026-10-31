package com.weatherwidget.shared.sourceview

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SourceDragRunTest {
    private val saves = mutableListOf<List<String>>()
    private val logs = mutableListOf<Pair<String, String>>()
    private val run = SourceDragRun(
        platform = "test",
        host = object : SourceDragRun.Host {
            override fun save(order: List<String>) {
                saves += order
            }

            override fun persistLog(tag: String, message: String) {
                logs += tag to message
            }
        },
    )

    private val before = listOf("NWS", "TOMORROW_IO")
    private val after = listOf("TOMORROW_IO", "NWS")

    @Test
    fun `a drop that changes the order saves once and logs both phases`() {
        run.start("TOMORROW_IO", before)

        assertTrue(run.finish(after))
        assertEquals(listOf(after), saves)
        assertEquals(
            listOf(
                SourceDragRun.TAG to "platform=test phase=start id=TOMORROW_IO order=[NWS, TOMORROW_IO]",
                SourceDragRun.TAG to
                    "platform=test phase=end id=TOMORROW_IO outcome=moved from=[NWS, TOMORROW_IO] to=[TOMORROW_IO, NWS]",
            ),
            logs,
        )
    }

    @Test
    fun `a drop back in place saves nothing but still logs the end`() {
        run.start("NWS", before)

        assertFalse(run.finish(before))
        assertTrue(saves.isEmpty())
        assertTrue(logs.last().second, "outcome=unchanged" in logs.last().second)
    }

    @Test
    fun `a second finish for one drag does nothing`() {
        run.start("NWS", before)
        run.finish(after)

        assertFalse(run.finish(before))
        assertEquals(1, saves.size)
        assertEquals(2, logs.size)
    }

    @Test
    fun `a finish with no drag started does nothing`() {
        assertFalse(run.finish(after))
        assertTrue(saves.isEmpty())
        assertTrue(logs.isEmpty())
    }
}
