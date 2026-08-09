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
        project.findProperty("WEATHER_API_KEY") as? String
            ?: localProperties.getProperty("WEATHER_API_KEY")
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
val releaseStoreFile =
    (
        project.findProperty("RELEASE_STORE_FILE") as? String
            ?: localProperties.getProperty("RELEASE_STORE_FILE")
            ?: System.getenv("RELEASE_STORE_FILE")
            ?: ""
    )
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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.weatherwidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 26080902
        versionName = "26080902"

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
        debug {
            // Pseudolocales for manual overflow/RTL QA (see
            // notes/260709-localization-testplan.md Tier 3): Settings > System > Languages
            // > App languages > Weather Widget offers "English (Accented, XA)" (~30% longer,
            // bracketed) and "Arabic (Bidi, XB)" (force-RTL) once installed as debug.
            isPseudoLocalesEnabled = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = releaseStoreFile
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

    lint {
        // Compile-time twin of LocaleResourceParityTest's key-parity check: aapt doesn't
        // catch missing/orphan translations, lint does. The translatable="false" hygiene
        // on pure-format/log-level base strings (see values/strings.xml) exists precisely
        // so this stays signal, not noise — keep both checks: lint gates release builds,
        // the JUnit test runs in the fast unit-test lane with a better failure message.
        error += setOf("MissingTranslation", "ExtraTranslation")
    }
}

val validateReleaseWeatherApiKey =
    tasks.register("validateReleaseWeatherApiKey") {
        group = "verification"
        description = "Fails release publication builds when the bundled WeatherAPI key is blank."
        inputs.property("weatherApiKeyConfigured", weatherApiKey.isNotBlank())
        doLast {
            if (inputs.properties["weatherApiKeyConfigured"] != true) {
                throw GradleException(
                    "WEATHER_API_KEY is required for Android release builds because setup may " +
                        "automatically enable WeatherAPI when NWS is unavailable.",
                )
            }
            logger.lifecycle("Android release WeatherAPI key check: configured")
        }
    }

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn(validateReleaseWeatherApiKey)
    }
}

tasks.withType<Test> {
    // Use a modest level of parallelism to speed up unit tests without
    // overwhelming Robolectric or exposing too much shared-state contention.
    //
    // The ceiling of 4 is measured, not arbitrary — do not raise it hoping for a speedup.
    // The four duration buckets run CONCURRENTLY, so peak JVM count is 4 x this value; at
    // 4 that is already 16 Robolectric JVMs, and each one occupies roughly two cores once
    // its GC and JIT threads are counted. On a 32-core host that saturates the machine.
    // Measured on 32 cores / 124 GB (2026-08-08), raising the cap 4 -> 8:
    //   Long bucket wall 41s -> 47s, Long CPU time 184s -> 321s (+74%), total wall
    //   54s -> 51-53s (inside run-to-run noise). Strictly more work for no gain.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    // Works around a recurring JVM C2 JIT crash (SIGSEGV in Node::uncast, libjvm.so) seen 3x
    // (2026-05-27, 2026-06-17, 2026-07-14 hs_err_pid*.log, gitignored) on long/heavy test runs,
    // always while C2 compiles one of these two SQLite/Room hot paths. Excluding them from C2
    // keeps them on the interpreter/C1 — imperceptible for tests, avoids the crash entirely.
    jvmArgs(
        "-XX:CompileCommand=exclude,android.database.sqlite.SQLiteProgram::<init>",
        "-XX:CompileCommand=exclude,androidx.room.driver.SupportSQLitePooledConnection::usePrepared",
    )
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Every unit test class declares exactly ONE of these category buckets: the three
// duration buckets, or a topic bucket (Localization). The buckets partition the suite —
// a localization test lives ONLY in Localization, not in a duration bucket — so the
// default run (unit-tests.sh / testByDurationDebugUnitTest) covers ALL buckets.
// validateUnitTestDurations enforces the exactly-one rule.
val unitTestCategoryBuckets =
    mapOf(
        "Short" to "com.weatherwidget.test.category.ShortDuration",
        "Medium" to "com.weatherwidget.test.category.MediumDuration",
        "Long" to "com.weatherwidget.test.category.LongDuration",
        "Localization" to "com.weatherwidget.test.category.Localization",
    )

val validateUnitTestDurations by tasks.registering {
    group = "verification"
    description =
        "Verifies unit test @Category usage: exactly one category bucket per file " +
        "(a duration OR a topic like Localization, never both), no duplicates, only known markers."

    val testFiles =
        fileTree("$projectDir/src/test/java") {
            include("**/*Test.kt")
            include("**/*Benchmark.kt")
        }

    inputs.files(testFiles)

    // Single source of truth: the marker whitelist derives from the bucket map, so a new
    // bucket added there is automatically legal in @Category and gets its own test task.
    val bucketNames = unitTestCategoryBuckets.values.map { it.substringAfterLast('.') }.toSet()

    doLast {
        val markerRegex = Regex("@Category\\(([^)]*)\\)")
        val violations = mutableListOf<String>()

        testFiles.files.sorted().forEach { file ->
            val content = file.readText()
            val bucketsInFile = mutableSetOf<String>()

            markerRegex.findAll(content).forEach { match ->
                val names =
                    match.groupValues[1]
                        .split(',')
                        .map { it.trim().removeSuffix("::class").substringAfterLast('.') }
                        .filter { it.isNotEmpty() }

                names.filterNot { it in bucketNames }.takeIf { it.isNotEmpty() }?.let {
                    violations += "${file.path}: unknown category marker(s) $it — known: ${bucketNames.sorted()}"
                }
                if (names.size > 1) {
                    violations += "${file.path}: more than one category in one @Category: $names"
                }
                bucketsInFile += names.filter { it in bucketNames }
            }

            // Exactly one bucket per file: zero means the tests silently run in NO bucket;
            // two means they run (and count) more than once across the full bucket run.
            if (bucketsInFile.size != 1) {
                violations += "${file.path}: expected exactly one category bucket, found ${bucketsInFile.ifEmpty { setOf("none") }}"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Unit test @Category violations:")
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

        tasks.register<Test>("test${bucketName}DebugUnitTest$taskSuffix") {
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

    unitTestCategoryBuckets.forEach { (bucketName, categoryClassName) ->
        registerDurationTestTask(bucketName, categoryClassName)
        registerDurationTestTask(bucketName, categoryClassName, forceExecution = true)
    }

    // Name kept for muscle memory / older docs; it runs ALL category buckets, including
    // the non-duration Localization bucket — the buckets partition the suite, so skipping
    // one would silently skip its tests entirely.
    tasks.register("testByDurationDebugUnitTest") {
        group = "verification"
        description = "Runs all debug unit test category buckets (short, medium, long, localization)."
        dependsOn(unitTestCategoryBuckets.keys.map { "test${it}DebugUnitTest" })
    }

    tasks.register("testByDurationDebugUnitTestFresh") {
        group = "verification"
        description =
            "Runs all debug unit test category buckets (short, medium, long, localization) " +
            "without reusing previous test task results."
        dependsOn(unitTestCategoryBuckets.keys.map { "test${it}DebugUnitTestFresh" })
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
    implementation(libs.ktor.client.okhttp)
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
    // Deliberately NO firebase-analytics: crash reporting only, which keeps the Play Data Safety
    // disclosures to location + crash data and matches the privacy policy (PRIVACY_POLICY.md).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.room.testing)
    testImplementation(libs.workmanager.testing)

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
