import java.io.File
import java.util.concurrent.Executors
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val weatherApiKey =
    (localProperties.getProperty("WEATHER_API_KEY")
        ?: System.getenv("WEATHER_API_KEY")
        ?: "")

ktlint {
    version.set("1.2.1")
    android.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
    // EditorConfig settings for Kotlin files
    additionalEditorconfig.set(
        mapOf(
            "ktlint_standard_no-wildcard-imports" to "disabled",
            "ktlint_standard_max-line-length" to "disabled",
        ),
    )
}

android {
    namespace = "com.weatherwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weatherwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WEATHER_API_KEY", "\"$weatherApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.workmanager)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Serialization
    implementation(libs.serialization.json)

    // Google Play Services
    implementation(libs.play.services.location)

    // Glide for GIF support in feature tour
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // Instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.ktor.client.mock)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.register("installDebugSmart") {
    group = "install"
    description = "Assemble and install debug APK with emulator wake/readiness preflight."
    dependsOn("assembleDebug")

    doLast {
        fun runCommand(
            command: List<String>,
            timeoutSeconds: Long = 10,
        ): String {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return ""
            }
            return process.inputStream.bufferedReader().readText().trim()
        }

        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/.Android/Sdk"
        val adbPath = listOf("$sdkRoot/platform-tools/adb", "adb")
            .firstOrNull { candidate ->
                runCatching { File(candidate).exists() || candidate == "adb" }.getOrDefault(false)
            }
            ?: error("adb not found. Install platform-tools or set ANDROID_SDK_ROOT.")

        val devicesOutput = runCommand(listOf(adbPath, "devices"))
        val deviceLines = devicesOutput
            .lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        fun parseAdbDeviceLine(line: String): Pair<String, String>? {
            val match = Regex("^(.*)\\s+(device|offline|unauthorized)\\s*$").find(line)
                ?: return null
            val serial = match.groupValues[1].trim()
            val state = match.groupValues[2]
            if (serial.isEmpty()) return null
            return serial to state
        }

        val targetSerials = deviceLines
            .mapNotNull { line ->
                val parsed = parseAdbDeviceLine(line) ?: return@mapNotNull null
                val (serial, state) = parsed
                if (state == "device") serial else null
            }
            .toList()

        if (targetSerials.isEmpty()) {
            error("No online Android devices found for install.")
        }

        logger.lifecycle("Target devices: ${targetSerials.joinToString(", ")}")

        val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apkFile.exists()) {
            error("Debug APK not found at ${apkFile.absolutePath}")
        }

        val failedInstalls = mutableListOf<String>()
        val maxParallel = minOf(targetSerials.size, 4)
        val executor = Executors.newFixedThreadPool(maxParallel)

        try {
            val futures = targetSerials.map { serial ->
                executor.submit<String> {
                    if (serial.startsWith("emulator-")) {
                        runCommand(listOf(adbPath, "-s", serial, "wait-for-device"), timeoutSeconds = 20)
                        runCommand(listOf(adbPath, "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"), timeoutSeconds = 3)
                        runCommand(listOf(adbPath, "-s", serial, "shell", "input", "keyevent", "82"), timeoutSeconds = 3)

                        var sdkLevel: String? = null
                        repeat(30) {
                            val sdk = runCommand(
                                listOf(adbPath, "-s", serial, "shell", "getprop", "ro.build.version.sdk"),
                                timeoutSeconds = 3,
                            ).replace("\r", "")
                            if (sdk.matches(Regex("\\d+"))) {
                                sdkLevel = sdk
                                return@repeat
                            }
                            Thread.sleep(1000)
                        }

                        if (sdkLevel != null) {
                            logger.lifecycle("Emulator $serial SDK detected: $sdkLevel")
                        } else {
                            logger.warn("Could not read emulator SDK level for $serial; install may fail if emulator is paused.")
                        }
                    }

                    val installOutput = runCommand(
                        listOf(adbPath, "-s", serial, "install", "-r", "-t", apkFile.absolutePath),
                        timeoutSeconds = 120,
                    )
                    if (!installOutput.contains("Success")) {
                        "$serial -> ${installOutput.ifBlank { "no output" }}"
                    } else {
                        logger.lifecycle("Install success on $serial")
                        ""
                    }
                }
            }

            futures.forEach { future ->
                val failure = future.get()
                if (failure.isNotEmpty()) {
                    failedInstalls += failure
                }
            }
        } finally {
            executor.shutdown()
        }

        if (failedInstalls.isNotEmpty()) {
            error("adb install failed on one or more devices:\n${failedInstalls.joinToString("\n")}")
        }
    }
}
