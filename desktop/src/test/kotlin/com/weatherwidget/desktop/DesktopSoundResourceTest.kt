package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopSoundResourceTest {

    @Test
    fun `extractSoundResource unpacks bundled ogg audio and is idempotent`() {
        val dir = Files.createTempDirectory("sound-extract-test")
        try {
            val first = extractSoundResource("/sounds/temperature_change.ogg", dir)
            assertNotNull(first)
            val bytes = Files.readAllBytes(first!!)
            assertTrue("expected non-empty ogg file", bytes.size > 100)
            assertEquals("OggS", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))

            val second = extractSoundResource("/sounds/temperature_change.ogg", dir)
            assertEquals(first, second)
            assertEquals(bytes.size.toLong(), Files.size(second!!))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
