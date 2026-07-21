import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

// --- Build-time API keys (parity with Android's BuildConfig) ---
// The desktop app, like Android, reads source API keys from local.properties (falling back to env
// vars) and bakes them into the binary. Without this the keyed sources (Silurian, WeatherAPI, OWM,
// Visual Crossing, Tomorrow.io) have no key on desktop and silently fall back to Open-Meteo.
// A key typed into the desktop Settings (config.apiKeys) still takes precedence at runtime.
run {
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    // -PpublicBuild bakes NO keys — required for artifacts published to the public apt repo
    // (scripts/apt-repo-publish.sh). Users can still enter their own keys in Settings.
    val publicBuild = providers.gradleProperty("publicBuild").isPresent
    fun keyFor(name: String): String =
        if (publicBuild) "" else localProps.getProperty(name) ?: System.getenv(name) ?: ""
    // Map of WeatherSource.id -> local.properties / env var name.
    val keySpecs = listOf(
        "WEATHER_API" to "WEATHER_API_KEY",
        "SILURIAN" to "SILURIAN_API_KEY",
        "OPEN_WEATHER_MAP" to "OPEN_WEATHER_MAP_API_KEY",
        "VISUAL_CROSSING" to "VISUAL_CROSSING_API_KEY",
        "TOMORROW_IO" to "TOMORROW_IO_API_KEY",
    )
    val generatedDir = layout.buildDirectory.dir("generated/apikeys/kotlin")
    val generateApiKeys = tasks.register("generateDesktopApiKeys") {
        val resolved = keySpecs.map { (id, prop) -> id to keyFor(prop) }
        resolved.forEach { (id, value) -> inputs.property(id, value) }
        val outDir = generatedDir
        outputs.dir(outDir)
        doLast {
            val pkgDir = outDir.get().asFile.resolve("com/weatherwidget/desktop").apply { mkdirs() }
            fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            val entries = resolved
                .filter { it.second.isNotBlank() }
                .joinToString("\n") { (id, value) -> "        \"$id\" to \"${esc(value)}\"," }
            pkgDir.resolve("DesktopApiKeys.kt").writeText(
                """
                |package com.weatherwidget.desktop
                |
                |// GENERATED — do not edit. See desktop/build.gradle.kts (generateDesktopApiKeys).
                |// Build-time API keys from local.properties / env, mirroring Android's BuildConfig keys.
                |internal object DesktopApiKeys {
                |    val DEFAULTS: Map<String, String> = mapOf(
                |$entries
                |    )
                |}
                |""".trimMargin()
            )
        }
    }
    kotlin.sourceSets.named("main") { kotlin.srcDir(generatedDir) }
    tasks.named("compileKotlin") { dependsOn(generateApiKeys) }
}

dependencies {
    // Reuse the pure-JVM weather layer (models + NWS/Open-Meteo API clients).
    implementation(project(":shared"))

    // Compose for Desktop (Skia-backed UI).
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.7.3")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")

    // Desktop HTTP engine for the shared Ktor clients + JSON negotiation.
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Swing dispatcher so Compose UI coroutines have a Main dispatcher on the JVM.
    implementation(libs.coroutines.swing)

    // Dorkbox SystemTray for native AppIndicator support on Linux.
    implementation("com.dorkbox:SystemTray:4.4")

    // Logging implementation for SLF4J (used by Ktor).
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.7.3")
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
}

// Bundle the genmon panel script into the jar (single source of truth: scripts/genmon-weather.py).
// The packaged app extracts it to a stable XDG path on first run so the panel command survives a
// repo move/removal.
tasks.named<Copy>("processResources") {
    from(rootProject.file("scripts/genmon-weather.py")) { into("scripts") }
}

compose.desktop {
    application {
        mainClass = "com.weatherwidget.desktop.MainKt"

        // Idle-CPU tuning. With the popup closed the app code is fully asleep, but the HotSpot JVM
        // still runs an internal heartbeat that keeps the process off 0%. Thread-level sampling
        // (strace futex attribution) showed the regular wakers are all JVM-internal:
        //   - VM Periodic Task Thread (~20Hz)  -> the hsperfdata sampler           => -XX:-UsePerfData
        //   - G1 Service + ~22 GC worker threads (overkill for a ~5MB heap)        => -XX:+UseSerialGC
        //   - the 1Hz "guaranteed safepoint" that wakes every thread when idle     => GuaranteedSafepointInterval=0
        //   - the Monitor Deflation Thread reclaiming idle locks                    => see below
        // Monitor deflation has TWO clocks: GuaranteedAsyncDeflationInterval (max gap, default 60s)
        // AND AsyncDeflationInterval (default 250ms) — the latter is the real waker. Per-thread
        // context-switch sampling on an idle (popup-closed) process showed "Monitor Deflati" waking
        // at exactly 4.00/s = one tick per 250ms, i.e. AsyncDeflationInterval, which an earlier pass
        // left at its default. Both must be 0 to stop the timer-driven passes (deflation then only
        // runs when the live-monitor count crosses MonitorUsedDeflationThreshold). This was ~46% of
        // all idle wakeups.
        // The diagnostic flags must be unlocked first or the JVM refuses to start. Setting the
        // interval flags to 0 makes those passes threshold-driven instead of clock-driven, so an
        // idle app stops waking for them. Trade-off of -UsePerfData: jps/jstat can't read this
        // process's counters (jcmd/jstack still attach fine).
        jvmArgs += listOf(
            "-XX:+UseSerialGC",
            "-XX:-UsePerfData",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:GuaranteedSafepointInterval=0",
            "-XX:GuaranteedAsyncDeflationInterval=0",
            "-XX:AsyncDeflationInterval=0",
        )

        // jpackage/jlink need a full JDK; Gradle here runs on Android Studio's JBR which omits
        // jpackage. Point packaging at a jpackage-capable JDK from env/property, else a known
        // local JDK if present. Left unset on machines where none is found (uses the toolchain).
        val packagingJdk = (System.getenv("JPACKAGE_HOME")
            ?: System.getProperty("jpackage.home")
            ?: listOf(
                "/usr/lib/jvm/java-21-openjdk-amd64",
            ).firstOrNull { file("$it/bin/jpackage").exists() })
        if (packagingJdk != null) {
            javaHome = packagingJdk
        }

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "weather-widget-desktop"
            packageVersion = "1.0.0"
            description = "Weather Widget — tray temperature + forecast accuracy"
            vendor = "weather-widget"

            // jpackage jlinks a MINIMIZED runtime. Without these, the packaged app crashes only when
            // installed (works fine via :desktop:run): java.sql for sqlite-jdbc/DriverManager, the
            // crypto modules for TLS to api.weather.gov, jdk.unsupported for JNA (Dorkbox tray).
            modules(
                "java.sql",
                "java.naming",
                "java.management",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )

            linux {
                shortcut = true
                menuGroup = "Utility"
                iconFile.set(project.file("icons/weather-widget.png"))
            }
        }
    }
}

tasks.register<JavaExec>("runDaemon") {
    group = "application"
    description = "Runs the headless daemon."
    mainClass.set("com.weatherwidget.desktop.MainKt")
    args("--daemon")
    val sourceSets = project.extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets
    classpath = sourceSets.getByName("main").runtimeClasspath
    jvmArgs(
        "-Djava.awt.headless=true",
        "-XX:+UseSerialGC",
        "-XX:-UsePerfData",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:GuaranteedSafepointInterval=0",
        "-XX:GuaranteedAsyncDeflationInterval=0",
        "-XX:AsyncDeflationInterval=0",
        "-XX:TieredStopAtLevel=1"
    )
}

tasks.register<JavaExec>("runUi") {
    group = "application"
    description = "Runs the UI process."
    mainClass.set("com.weatherwidget.desktop.MainKt")
    args("--ui")
    val sourceSets = project.extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets
    classpath = sourceSets.getByName("main").runtimeClasspath
    jvmArgs(
        "-XX:+UseSerialGC",
        "-XX:-UsePerfData",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:GuaranteedSafepointInterval=0",
        "-XX:GuaranteedAsyncDeflationInterval=0",
        "-XX:AsyncDeflationInterval=0"
    )
}

// ---------------------------------------------------------------------------
// Unit test categories — the app module's convention, ported to :desktop.
//
// Every test class declares exactly ONE bucket. The buckets partition the suite, so running all
// three covers everything; validateDesktopTestCategories fails the build if a class declares zero
// (it would run in NO bucket) or more than one (it would run, and be counted, twice).
// Markers live in desktop/src/test/kotlin/com/weatherwidget/test/category/.
// ---------------------------------------------------------------------------
val desktopTestCategoryBuckets =
    mapOf(
        "Short" to "com.weatherwidget.test.category.ShortDuration",
        "Medium" to "com.weatherwidget.test.category.MediumDuration",
        "Long" to "com.weatherwidget.test.category.LongDuration",
    )

val validateDesktopTestCategories by tasks.registering {
    group = "verification"
    description =
        "Verifies :desktop unit test @Category usage: exactly one duration bucket per file, " +
        "no duplicates, only known markers."

    val testFiles =
        fileTree("$projectDir/src/test/kotlin") {
            include("**/*Test.kt")
            include("**/*Benchmark.kt")
        }

    inputs.files(testFiles)

    // Single source of truth: the marker whitelist derives from the bucket map, so a new bucket
    // added there is automatically legal in @Category and gets its own test task.
    val bucketNames = desktopTestCategoryBuckets.values.map { it.substringAfterLast('.') }.toSet()

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

            if (bucketsInFile.size != 1) {
                violations += "${file.path}: expected exactly one category bucket, found ${bucketsInFile.ifEmpty { setOf("none") }}"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Desktop unit test @Category violations:")
                    violations.forEach { violation -> appendLine(" - $violation") }
                },
            )
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(validateDesktopTestCategories)
}

desktopTestCategoryBuckets.forEach { (bucketName, categoryClassName) ->
    listOf(false, true).forEach { forceExecution ->
        val taskSuffix = if (forceExecution) "Fresh" else ""
        tasks.register<Test>("test${bucketName}Desktop$taskSuffix") {
            group = "verification"
            description =
                if (forceExecution) {
                    "Runs ${bucketName.lowercase()} desktop unit tests without reusing previous results."
                } else {
                    "Runs ${bucketName.lowercase()} desktop unit tests."
                }

            val sourceSets =
                project.extensions.getByType<org.gradle.api.plugins.JavaPluginExtension>().sourceSets
            testClassesDirs = sourceSets.getByName("test").output.classesDirs
            classpath = sourceSets.getByName("test").runtimeClasspath

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
}

tasks.register("testByDurationDesktop") {
    group = "verification"
    description = "Runs all :desktop unit test category buckets (short, medium, long)."
    dependsOn(desktopTestCategoryBuckets.keys.map { "test${it}Desktop" })
}

tasks.register("testByDurationDesktopFresh") {
    group = "verification"
    description =
        "Runs all :desktop unit test category buckets (short, medium, long) without reusing results."
    dependsOn(desktopTestCategoryBuckets.keys.map { "test${it}DesktopFresh" })
}


