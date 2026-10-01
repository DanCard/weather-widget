package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

@Category(ShortDuration::class)
class SystemTimeZoneWatchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val la = ZoneId.of("America/Los_Angeles")

    private fun link(target: String): Path {
        val p = tmp.root.toPath().resolve("localtime")
        Files.deleteIfExists(p)
        return Files.createSymbolicLink(p, Path.of(target))
    }

    private val noTimezoneFile: Path get() = tmp.root.toPath().resolve("absent-timezone")

    @Test
    fun `reads zone from relative debian-style symlink`() {
        assertEquals(
            "America/Los_Angeles",
            SystemTimeZoneWatch.readSystemZoneId(link("../usr/share/zoneinfo/America/Los_Angeles"), noTimezoneFile),
        )
    }

    @Test
    fun `reads zone from absolute symlink and strips posix prefix`() {
        assertEquals("Europe/Warsaw", SystemTimeZoneWatch.readSystemZoneId(link("/usr/share/zoneinfo/Europe/Warsaw"), noTimezoneFile))
        assertEquals("Europe/Warsaw", SystemTimeZoneWatch.zoneIdFromLinkTarget("/usr/share/zoneinfo/posix/Europe/Warsaw"))
    }

    @Test
    fun `non-symlink localtime falls back to etc timezone`() {
        val copied = tmp.newFile("localtime-copy").toPath()
        val tzFile = tmp.newFile("timezone").toPath().also { Files.writeString(it, "Europe/Warsaw\n") }
        assertEquals("Europe/Warsaw", SystemTimeZoneWatch.readSystemZoneId(copied, tzFile))
    }

    @Test
    fun `unknown when neither source names a valid zone`() {
        val copied = tmp.newFile("localtime-copy").toPath()
        assertNull(SystemTimeZoneWatch.readSystemZoneId(copied, noTimezoneFile))
        assertNull(SystemTimeZoneWatch.zoneIdFromLinkTarget("/usr/share/zoneinfo/Not/AZone"))
        assertNull(SystemTimeZoneWatch.zoneIdFromLinkTarget("/somewhere/else"))
    }

    @Test
    fun `restarts when the system zone moved and the JVM is still on the old one`() {
        // The 2026-09-30 case: JVM cached Warsaw, system switched to Los Angeles.
        assertTrue(SystemTimeZoneWatch.shouldRestartForZoneChange("Europe/Warsaw", "America/Los_Angeles", warsaw))
    }

    @Test
    fun `no restart when the system zone is unchanged since start`() {
        assertFalse(SystemTimeZoneWatch.shouldRestartForZoneChange("Europe/Warsaw", "Europe/Warsaw", warsaw))
    }

    @Test
    fun `no restart loop when JVM and file name the zone differently`() {
        // The successor reads its own baseline, so a naming mismatch with the JVM can't re-trigger.
        assertFalse(SystemTimeZoneWatch.shouldRestartForZoneChange("US/Pacific", "US/Pacific", la))
    }

    @Test
    fun `no restart for an alias change with identical rules`() {
        assertFalse(SystemTimeZoneWatch.shouldRestartForZoneChange("America/Los_Angeles", "US/Pacific", la))
    }

    @Test
    fun `no restart when the current zone cannot be read`() {
        assertFalse(SystemTimeZoneWatch.shouldRestartForZoneChange("Europe/Warsaw", null, warsaw))
    }

    @Test
    fun `no restart when the JVM already uses the new zone`() {
        assertFalse(SystemTimeZoneWatch.shouldRestartForZoneChange("Europe/Warsaw", "America/Los_Angeles", la))
    }

    @Test
    fun `TZ env disables the watch`() {
        assertTrue(SystemTimeZoneWatch.isWatchable(emptyMap()))
        assertFalse(SystemTimeZoneWatch.isWatchable(mapOf("TZ" to "Europe/Warsaw")))
    }
}
