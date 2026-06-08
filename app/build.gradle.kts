import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

// Apply Firebase (Crashlytics) plugins only when google-services.json is present, so the everyday
// build works without it. Drop the file (from your Firebase project) into app/ to enable crash upload.
val googleServicesJson = file("google-services.json")
if (googleServicesJson.exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    logger.lifecycle(
        "Firebase Crashlytics disabled: app/google-services.json not found. " +
            "Crashes are still captured locally (app_logs / Share logs); add the file to enable upload.",
    )
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val weatherApiKey =
    (
        localProperties.getProperty("WEATHER_API_KEY")
            ?: System.getenv("WEATHER_API_KEY")
            ?: ""
    )
val silurianApiKey =
    (
        localProperties.getProperty("SILURIAN_API_KEY")
            ?: System.getenv("SILURIAN_API_KEY")
            ?: ""
    )
val openWeatherMapApiKey =
    (
        localProperties.getProperty("OPEN_WEATHER_MAP_API_KEY")
            ?: System.getenv("OPEN_WEATHER_MAP_API_KEY")
            ?: ""
    )
val visualCrossingApiKey =
    (
        localProperties.getProperty("VISUAL_CROSSING_API_KEY")
            ?: System.getenv("VISUAL_CROSSING_API_KEY")
            ?: ""
    )
val tomorrowIoApiKey =
    (
        localProperties.getProperty("TOMORROW_IO_API_KEY")
            ?: System.getenv("TOMORROW_IO_API_KEY")
            ?: ""
    )

// Release signing secrets are read from gradle.properties (global or local), local.properties,
// or the environment (never committed). They are empty for debug-only builds;
// `assembleRelease` requires them to be set.
val releaseStorePassword =
    (
        project.findProperty("RELEASE_STORE_PASSWORD") as? String
            ?: localProperties.getProperty("RELEASE_STORE_PASSWORD")
            ?: System.getenv("RELEASE_STORE_PASSWORD")
            ?: ""
    )
val releaseKeyPassword =
    (
        project.findProperty("RELEASE_KEY_PASSWORD") as? String
            ?: localProperties.getProperty("RELEASE_KEY_PASSWORD")
            ?: System.getenv("RELEASE_KEY_PASSWORD")
            ?: ""
    )
val releaseKeyAlias =
    (
        project.findProperty("RELEASE_KEY_ALIAS") as? String
            ?: localProperties.getProperty("RELEASE_KEY_ALIAS")
            ?: "weatherwidget"
    )

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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.weatherwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.weatherwidget.WeatherWidgetTestRunner"
        buildConfigField("String", "WEATHER_API_KEY", "\"$weatherApiKey\"")
        buildConfigField("String", "SILURIAN_API_KEY", "\"$silurianApiKey\"")
        buildConfigField("String", "OPEN_WEATHER_MAP_API_KEY", "\"$openWeatherMapApiKey\"")
        buildConfigField("String", "VISUAL_CROSSING_API_KEY", "\"$visualCrossingApiKey\"")
        buildConfigField("String", "TOMORROW_IO_API_KEY", "\"$tomorrowIoApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as? String
            storeFile = if (!storeFilePath.isNullOrBlank()) file(storeFilePath) else rootProject.file("release.keystore")
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes.all {
        if (name == "release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test> {
    // Use a modest level of parallelism to speed up unit tests without
    // overwhelming Robolectric or exposing too much shared-state contention.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val unitTestDurationCategories =
    mapOf(
        "Short" to "com.weatherwidget.test.category.ShortDuration",
        "Medium" to "com.weatherwidget.test.category.MediumDuration",
        "Long" to "com.weatherwidget.test.category.LongDuration",
    )

val validateUnitTestDurations by tasks.registering {
    group = "verification"
    description = "Verifies that each unit test class declares exactly one duration category."

    val testFiles =
        fileTree("$projectDir/src/test/java") {
            include("**/*Test.kt")
            include("**/*Benchmark.kt")
        }

    inputs.files(testFiles)

    doLast {
        val markerRegex = Regex("@Category\\(([^)]*)\\)")
        val durationNames = setOf("ShortDuration", "MediumDuration", "LongDuration")
        val violations = mutableListOf<String>()

        testFiles.files.sorted().forEach { file ->
            val content = file.readText()
            val categoryMatches =
                markerRegex
                    .findAll(content)
                    .map { matchResult ->
                        durationNames.filter { durationName -> durationName in matchResult.groupValues[1] }
                    }.flatten()
                    .toList()
                    .distinct()

            if (categoryMatches.size != 1) {
                violations += "${file.path}: expected exactly one duration category, found ${categoryMatches.ifEmpty { listOf("none") }}"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Unit tests must declare exactly one duration category:")
                    violations.forEach { violation -> appendLine(" - $violation") }
                },
            )
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(validateUnitTestDurations)
}

afterEvaluate {
    val testDebugUnitTest = tasks.named<Test>("testDebugUnitTest")

    fun registerDurationTestTask(
        bucketName: String,
        categoryClassName: String,
        forceExecution: Boolean = false,
    ) {
        val taskSuffix = if (forceExecution) "Fresh" else ""

        tasks.register<Test>("test${bucketName}DebugUnitTest${taskSuffix}") {
            group = "verification"
            description =
                if (forceExecution) {
                    "Runs ${bucketName.lowercase()} debug unit tests without reusing previous test task results."
                } else {
                    "Runs ${bucketName.lowercase()} debug unit tests."
                }

            dependsOn(
                "compileDebugUnitTestSources",
                "generateDebugUnitTestConfig",
                "packageDebugUnitTestForUnitTest",
                "processDebugUnitTestJavaRes",
                "transformDebugUnitTestClassesWithAsm",
            )

            val baseTask = testDebugUnitTest.get()
            testClassesDirs = baseTask.testClassesDirs
            classpath = baseTask.classpath
            maxParallelForks = baseTask.maxParallelForks
            testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            if (forceExecution) {
                outputs.upToDateWhen { false }
            }
            useJUnit {
                includeCategories(categoryClassName)
            }
        }
    }

    unitTestDurationCategories.forEach { (bucketName, categoryClassName) ->
        registerDurationTestTask(bucketName, categoryClassName)
        registerDurationTestTask(bucketName, categoryClassName, forceExecution = true)
    }

    tasks.register("testByDurationDebugUnitTest") {
        group = "verification"
        description = "Runs the short, medium, and long debug unit test buckets."
        dependsOn(unitTestDurationCategories.keys.map { "test${it}DebugUnitTest" })
    }

    tasks.register("testByDurationDebugUnitTestFresh") {
        group = "verification"
        description = "Runs the short, medium, and long debug unit test buckets without reusing previous test task results."
        dependsOn(unitTestDurationCategories.keys.map { "test${it}DebugUnitTestFresh" })
    }
}

dependencies {
    // Shared pure-JVM module: weather models + NWS/Open-Meteo API clients (also used by :desktop).
    implementation(project(":shared"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.flexbox)

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

    // Firebase Crashlytics (push crash reporting). The libraries compile/run without
    // google-services.json — Firebase simply no-ops init — but only upload once the file is added
    // and the plugins above are applied. BoM keeps the artifact versions aligned.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.room.testing)

    // Instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.serialization.json)
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
            val process =
                ProcessBuilder(command)
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
        val adbPath =
            listOf("$sdkRoot/platform-tools/adb", "adb")
                .firstOrNull { candidate ->
                    runCatching { File(candidate).exists() || candidate == "adb" }.getOrDefault(false)
                }
                ?: error("adb not found. Install platform-tools or set ANDROID_SDK_ROOT.")

        val devicesOutput = runCommand(listOf(adbPath, "devices"))
        val deviceLines =
            devicesOutput
                .lineSequence()
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

        fun parseAdbDeviceLine(line: String): Pair<String, String>? {
            val match =
                Regex("^(.*)\\s+(device|offline|unauthorized)\\s*$").find(line)
                    ?: return null
            val serial = match.groupValues[1].trim()
            val state = match.groupValues[2]
            if (serial.isEmpty()) return null
            return serial to state
        }

        val targetSerials =
            deviceLines
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
            val futures =
                targetSerials.map { serial ->
                    executor.submit<String> {
                        if (serial.startsWith("emulator-")) {
                            runCommand(listOf(adbPath, "-s", serial, "wait-for-device"), timeoutSeconds = 20)
                            runCommand(listOf(adbPath, "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"), timeoutSeconds = 3)
                            runCommand(listOf(adbPath, "-s", serial, "shell", "input", "keyevent", "82"), timeoutSeconds = 3)

                            var sdkLevel: String? = null
                            repeat(30) {
                                val sdk =
                                    runCommand(
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

                        val installOutput =
                            runCommand(
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

// Task to trigger widget refresh after instrumentation tests
// This helps recover the widget UI after force-stop during testing
tasks.register("refreshWidgetAfterTests") {
    description = "Triggers widget refresh after running instrumentation tests (for manual testing)"
    group = "verification"

    doLast {
        try {
            val adbPath = "${System.getenv(
                "ANDROID_HOME",
            ) ?: System.getenv("ANDROID_SDK_ROOT") ?: System.getProperty("user.home") + "/.Android/Sdk"}/platform-tools/adb"

            // Find the first emulator (ignore physical devices)
            val listProcess =
                ProcessBuilder(adbPath, "devices")
                    .redirectErrorStream(true)
                    .start()

            listProcess.waitFor(5, TimeUnit.SECONDS)
            val devicesOutput = listProcess.inputStream.bufferedReader().use { it.readText() }

            // Prefer the device the test run actually targeted (emulator-tests.sh exports
            // ANDROID_SERIAL); otherwise fall back to the first connected emulator. Targeting the
            // wrong emulator here adds needless adb traffic at the boundary between sequential
            // per-emulator runs, which can flip the next emulator to "offline".
            val targetedSerial = System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }
            val emulatorSerial =
                targetedSerial
                    ?: devicesOutput.lines()
                        .firstOrNull { it.contains("emulator-") && it.contains("device") }
                        ?.split("\t")?.getOrNull(0)

            if (emulatorSerial != null) {
                val adbCommand =
                    listOf(
                        adbPath,
                        "-s",
                        emulatorSerial,
                        "shell",
                        "am",
                        "broadcast",
                        "-a",
                        "com.weatherwidget.ACTION_REFRESH",
                        "-p",
                        "com.weatherwidget",
                    )

                val process =
                    ProcessBuilder(adbCommand)
                        .redirectErrorStream(true)
                        .start()

                process.waitFor(10, TimeUnit.SECONDS)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                logger.lifecycle("Widget refresh broadcast sent to $emulatorSerial: ${output.ifBlank { "completed" }}")
            } else {
                logger.warn("No emulator found connected. Skipping widget refresh.")
            }
        } catch (e: Exception) {
            logger.warn("Failed to trigger widget refresh: ${e.message}")
        }
    }
}

// Automatically refresh widget after connected tests complete
gradle.projectsEvaluated {
    tasks.findByName("connectedDebugAndroidTest")?.let { testTask ->
        testTask.finalizedBy("refreshWidgetAfterTests")
    }
}
