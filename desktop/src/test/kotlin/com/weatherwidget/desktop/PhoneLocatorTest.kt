package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PhoneLocatorTest {
    @Test
    fun `parseAdbDevices parses serial and state`() {
        val devices = PhoneLocator.parseAdbDevices(
            """
            List of devices attached
            emulator-5554	device
            RFCT71FR9NT	device
            offline-device	offline
            """.trimIndent(),
        )

        assertEquals(3, devices.size)
        assertEquals("emulator-5554", devices[0].serial)
        assertEquals("device", devices[0].state)
        assertEquals(true, devices[0].isEmulator)
        assertEquals("RFCT71FR9NT", devices[1].serial)
        assertEquals(false, devices[1].isEmulator)
    }

    @Test
    fun `locate skips emulator and targets real phone serial`() {
        val runner = RecordingCommandRunner(
            mapOf(
                listOf("adb", "devices") to """
                    List of devices attached
                    emulator-5554	device
                    RFCT71FR9NT	device
                """.trimIndent(),
                listOf("adb", "-s", "RFCT71FR9NT", "shell", "getprop", "ro.kernel.qemu") to "0\n",
                listOf("adb", "-s", "RFCT71FR9NT", "shell", "cat", "/proc/uptime") to "300.0 1200.0\n",
                listOf("adb", "-s", "RFCT71FR9NT", "shell", "dumpsys", "location") to
                    "last location=Location[gps 37.4168,-122.0890 hAcc=8.0 et=+3m5s]",
            ),
        )

        val location = PhoneLocator(runner, adbExecutable = "adb").locate()

        assertEquals("RFCT71FR9NT", location?.serial)
        assertEquals(37.4168, location?.lat ?: 0.0, 0.000001)
        assertEquals(
            listOf(
                listOf("adb", "devices"),
                listOf("adb", "-s", "RFCT71FR9NT", "shell", "getprop", "ro.kernel.qemu"),
                listOf("adb", "-s", "RFCT71FR9NT", "shell", "cat", "/proc/uptime"),
                listOf("adb", "-s", "RFCT71FR9NT", "shell", "dumpsys", "location"),
            ),
            runner.commands,
        )
    }

    @Test
    fun `locate returns null when only emulator is connected`() {
        val runner = RecordingCommandRunner(
            mapOf(
                listOf("adb", "devices") to """
                    List of devices attached
                    emulator-5554	device
                """.trimIndent(),
            ),
        )

        val location = PhoneLocator(runner, adbExecutable = "adb").locate()

        assertNull(location)
        assertEquals(listOf(listOf("adb", "devices")), runner.commands)
    }

    @Test
    fun `parseBestLocation prefers gps over fused`() {
        val dumpsys = """
            last location=Location[fused 10.0,20.0 hAcc=25.0 et=+2h]
            last location=Location[gps 37.4168,-122.0890 hAcc=8.0 et=+3m5s]
        """.trimIndent()

        val location = PhoneLocator.parseBestLocation(dumpsys, uptimeMillis = 200_000L)

        assertEquals("gps", location?.provider)
        assertEquals(37.4168, location?.lat ?: 0.0, 0.000001)
        assertEquals(-122.0890, location?.lon ?: 0.0, 0.000001)
        assertEquals(8.0, location?.accuracyMeters ?: 0.0, 0.000001)
        assertEquals(15_000L, location?.fixAgeMillis)
    }

    @Test
    fun `parseLocationLine treats et as elapsed realtime timestamp`() {
        val location = PhoneLocator.parseLocationLine(
            "last location=Location[gps 37.416883,-122.089009 hAcc=9.8 et=+12d22h56m41s540ms]",
            uptimeMillis = 1_119_474_410L,
        )

        assertEquals(72_870L, location?.fixAgeMillis)
    }

    @Test
    fun `parseLocationLine returns null for unrelated lines`() {
        assertNull(PhoneLocator.parseLocationLine("no location here"))
    }

    @Test
    fun `parseBestLocation ignores non gps and fused providers`() {
        val dumpsys = "last location=Location[network 10.0,20.0 hAcc=250.0 et=+1m]"

        assertNull(PhoneLocator.parseBestLocation(dumpsys))
    }

    @Test
    fun `parseElapsedAgeMillis handles days hours minutes seconds and millis`() {
        assertEquals(93_845_250L, PhoneLocator.parseElapsedAgeMillis("+1d2h4m5s250ms"))
    }

    @Test
    fun `parseProcUptimeMillis reads first uptime field`() {
        assertEquals(1_119_474_410L, PhoneLocator.parseProcUptimeMillis("1119474.41 8006779.56"))
    }

    @Test
    fun `locate falls back to sdk adb path when adb is not on path`() {
        val sdkAdb = "${System.getProperty("user.home")}/.Android/Sdk/platform-tools/adb"
        val runner = RecordingCommandRunner(
            mapOf(
                listOf(sdkAdb, "version") to "Android Debug Bridge version\n",
                listOf(sdkAdb, "devices") to """
                    List of devices attached
                    RFCT71FR9NT	device
                """.trimIndent(),
                listOf(sdkAdb, "-s", "RFCT71FR9NT", "shell", "getprop", "ro.kernel.qemu") to "0\n",
                listOf(sdkAdb, "-s", "RFCT71FR9NT", "shell", "cat", "/proc/uptime") to "300.0 1200.0\n",
                listOf(sdkAdb, "-s", "RFCT71FR9NT", "shell", "dumpsys", "location") to
                    "last location=Location[fused 37.4168,-122.0890 hAcc=8.0 et=+4m50s]",
            ),
        )

        val location = PhoneLocator(runner).locate()

        assertEquals("RFCT71FR9NT", location?.serial)
        assertEquals(10_000L, location?.fixAgeMillis)
        assertEquals(listOf("adb", "version"), runner.commands.first())
        assertEquals(listOf(sdkAdb, "version"), runner.commands[1])
        assertEquals(listOf(sdkAdb, "devices"), runner.commands[2])
    }

    private class RecordingCommandRunner(
        private val responses: Map<List<String>, String>,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(
            command: List<String>,
            timeoutMillis: Long,
        ): Result<String> {
            commands += command
            return responses[command]?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Unexpected command: $command"))
        }
    }
}
