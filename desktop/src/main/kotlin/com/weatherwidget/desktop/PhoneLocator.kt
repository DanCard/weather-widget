package com.weatherwidget.desktop

import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit

class PhoneLocator(
    private val commandRunner: CommandRunner = ProcessCommandRunner,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val adbExecutable: String? = null,
) {
    fun connectedRealDevices(): List<AdbDevice> {
        val adb = resolveAdbExecutable() ?: return emptyList()
        return connectedRealDevices(adb)
    }

    private fun connectedRealDevices(adb: String): List<AdbDevice> {
        val devices = commandRunner.run(listOf(adb, "devices"), timeoutMillis = 5_000)
            .getOrNull()
            ?: return emptyList()
        return parseAdbDevices(devices)
            .filter { it.state == "device" }
            .map { device ->
                if (device.serial.startsWith("emulator-")) {
                    device.copy(isEmulator = true)
                } else {
                    val qemu = commandRunner.run(
                        listOf(adb, "-s", device.serial, "shell", "getprop", "ro.kernel.qemu"),
                        timeoutMillis = 5_000,
                    ).getOrNull()?.trim()
                    device.copy(isEmulator = qemu == "1")
                }
            }
            .filterNot { it.isEmulator }
    }

    fun isAvailable(): Boolean = connectedRealDevices().isNotEmpty()

    fun locate(log: (String) -> Unit = {}): PhoneLocation? {
        log("Checking ADB for connected real phones...")
        val adb = resolveAdbExecutable()
        if (adb == null) {
            log("ADB executable was not found.")
            return null
        }
        log("Using ADB: $adb")
        val devices = connectedRealDevices(adb)
        if (devices.isEmpty()) {
            log("No real phone is available through ADB.")
            return null
        }

        devices.forEachIndexed { index, device ->
            log("Trying phone ${index + 1}/${devices.size}: ${device.serial}")
            val uptimeMillis = commandRunner.run(
                listOf(adb, "-s", device.serial, "shell", "cat", "/proc/uptime"),
                timeoutMillis = 5_000,
            ).getOrElse { error ->
                log("Phone ${device.serial}: uptime read failed: ${error.message ?: error::class.simpleName}")
                return@forEachIndexed
            }.let { parseProcUptimeMillis(it) }

            if (uptimeMillis == null) {
                log("Phone ${device.serial}: uptime parse failed.")
                return@forEachIndexed
            }

            val dumpsys = commandRunner.run(
                listOf(adb, "-s", device.serial, "shell", "dumpsys", "location"),
                timeoutMillis = 10_000,
            ).getOrElse { error ->
                log("Phone ${device.serial}: location dump failed: ${error.message ?: error::class.simpleName}")
                return@forEachIndexed
            }
            val location = parseBestLocation(dumpsys, nowMillis(), uptimeMillis)
            if (location != null) {
                val age = location.fixAgeMillis?.let { formatDuration(it) } ?: "unknown age"
                log("Phone ${device.serial}: found ${location.provider} fix ($age old).")
                return location.copy(serial = device.serial)
            }
            log("Phone ${device.serial}: no gps/fused last-known fix found.")
        }

        log("No usable phone gps/fused location found.")
        return null
    }

    companion object {
        private val locationRegex =
            Regex("""Location\[(\w+)\s+(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)([^\]]*)]""")
        private val accuracyRegex = Regex("""\bhAcc=([0-9]+(?:\.[0-9]+)?)""")
        private val elapsedRealtimeRegex = Regex("""\bet=([+0-9a-zA-Z.]+)""")
        private val DEFAULT_ADB_CANDIDATES = listOf(
            "adb",
            "${System.getProperty("user.home")}/.Android/Sdk/platform-tools/adb",
            "${System.getenv("ANDROID_HOME").orEmpty()}/platform-tools/adb",
            "${System.getenv("ANDROID_SDK_ROOT").orEmpty()}/platform-tools/adb",
        ).filter { it.isNotBlank() && !it.startsWith("/platform-tools/") }

        fun parseBestLocation(
            dumpsys: String,
            nowMillis: Long = System.currentTimeMillis(),
            uptimeMillis: Long? = null,
        ): PhoneLocation? {
            val locations = dumpsys.lineSequence()
                .mapNotNull { parseLocationLine(it, nowMillis, uptimeMillis) }
                .toList()
            return locations.firstOrNull { it.provider == "gps" }
                ?: locations.firstOrNull { it.provider == "fused" }
        }

        fun parseAdbDevices(output: String): List<AdbDevice> =
            output.lineSequence()
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    val serial = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val state = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    AdbDevice(
                        serial = serial,
                        state = state,
                        isEmulator = serial.startsWith("emulator-"),
                    )
                }
                .toList()

        fun parseLocationLine(
            line: String,
            nowMillis: Long = System.currentTimeMillis(),
            uptimeMillis: Long? = null,
        ): PhoneLocation? {
            val match = locationRegex.find(line) ?: return null
            val provider = match.groupValues[1]
            val lat = match.groupValues[2].toDoubleOrNull() ?: return null
            val lon = match.groupValues[3].toDoubleOrNull() ?: return null
            val extras = match.groupValues[4]
            val accuracyMeters = accuracyRegex.find(extras)?.groupValues?.get(1)?.toDoubleOrNull()
            val elapsedRealtimeMillis = elapsedRealtimeRegex.find(extras)
                ?.groupValues
                ?.get(1)
                ?.let { parseElapsedDurationMillis(it) }
            val ageMillis = elapsedRealtimeMillis?.let { elapsed ->
                uptimeMillis?.let { uptime -> (uptime - elapsed).coerceAtLeast(0L) }
            }
            return PhoneLocation(
                lat = lat,
                lon = lon,
                provider = provider,
                accuracyMeters = accuracyMeters,
                fixAgeMillis = ageMillis,
                observedAtMillis = ageMillis?.let { nowMillis - it },
            )
        }

        fun parseElapsedDurationMillis(raw: String): Long? {
            val value = raw.removePrefix("+")
            if (value.isBlank()) return null
            var total = 0L
            var matched = false
            Regex("""([0-9]+(?:\.[0-9]+)?)(ms|d|h|m|s)""")
                .findAll(value)
                .forEach { match ->
                    matched = true
                    val amount = match.groupValues[1].toDouble()
                    total += when (match.groupValues[2]) {
                        "d" -> (amount * 86_400_000).toLong()
                        "h" -> (amount * 3_600_000).toLong()
                        "m" -> (amount * 60_000).toLong()
                        "s" -> (amount * 1_000).toLong()
                        "ms" -> amount.toLong()
                        else -> 0L
                    }
                }
            return total.takeIf { matched }
        }

        fun parseProcUptimeMillis(output: String): Long? =
            output.trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.toDoubleOrNull()
                ?.let { (it * 1_000).toLong() }

        fun parseElapsedAgeMillis(raw: String): Long? = parseElapsedDurationMillis(raw)

        private fun formatDuration(durationMillis: Long): String {
            val seconds = durationMillis / 1_000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 -> "${days}d ${hours % 24}h"
                hours > 0 -> "${hours}h ${minutes % 60}m"
                minutes > 0 -> "${minutes}m ${seconds % 60}s"
                else -> "${seconds}s"
            }
        }
    }

    private fun resolveAdbExecutable(): String? {
        adbExecutable?.let { return it }
        return DEFAULT_ADB_CANDIDATES.firstOrNull { candidate ->
            commandRunner.run(listOf(candidate, "version"), timeoutMillis = 5_000).isSuccess
        }
    }
}

data class PhoneLocation(
    val lat: Double,
    val lon: Double,
    val provider: String,
    val accuracyMeters: Double?,
    val fixAgeMillis: Long?,
    val observedAtMillis: Long?,
    val serial: String? = null,
)

data class AdbDevice(
    val serial: String,
    val state: String,
    val isEmulator: Boolean,
)

interface CommandRunner {
    fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): Result<String>
}

private object ProcessCommandRunner : CommandRunner {
    override fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): Result<String> = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val readerThread = thread(start = true, isDaemon = true, name = "adb-command-output-reader") {
            process.inputStream.bufferedReader().use { reader ->
                output.append(reader.readText())
            }
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            readerThread.join(1_000)
            return Result.failure(IllegalStateException("Timed out running ${command.firstOrNull()}"))
        }
        readerThread.join(1_000)
        val outputText = output.toString()
        if (process.exitValue() != 0) {
            throw IllegalStateException(outputText.ifBlank { "Command failed: ${command.joinToString(" ")}" })
        }
        outputText
    }
}
