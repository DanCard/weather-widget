package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class AppLogTest {
    @After
    fun tearDown() = AppLog.resetToDefault()

    @Test
    fun `routes d, i, w, e to installed sink and drops VERBOSE`() {
        val captured = mutableListOf<Triple<String, String, String>>()
        AppLog.install { tag, msg, level -> captured += Triple(tag, msg, level) }

        AppLog.d("T", "debug")
        AppLog.i("T", "info")
        AppLog.w("T", "warn")
        AppLog.e("T", "error")
        AppLog.log("T", "verbose", "VERBOSE")

        assertEquals(
            listOf(
                Triple("T", "debug", "DEBUG"),
                Triple("T", "info", "INFO"),
                Triple("T", "warn", "WARN"),
                Triple("T", "error", "ERROR"),
            ),
            captured,
        )
    }

    @Test
    fun `resetToDefault restores silent no-op sink`() {
        val captured = mutableListOf<String>()
        AppLog.install { _, msg, _ -> captured += msg }
        AppLog.resetToDefault()

        AppLog.w("T", "ignored")
        assertTrue(captured.isEmpty())
    }
}
