package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SwitchRefreshSignalTest {
    @Test
    fun `each signal survives the socket byte and the plain data byte is none of them`() {
        SwitchRefreshSignal.entries.forEach { assertEquals(it, SwitchRefreshSignal.of(it.byte)) }
        assertNull(SwitchRefreshSignal.of('U'.code.toByte()))
    }
}
