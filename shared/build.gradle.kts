plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // HTTP + JSON. Engine is supplied by each consumer (Android: ktor-client-android,
    // desktop: ktor-client-cio), so this module depends only on ktor-client-core.
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.json)
    api(libs.serialization.json)
    api(libs.coroutines.core)
    api(libs.sqlite.jdbc)

    // @Inject annotations on the API client constructors are inert here (Hilt's AppModule
    // provides them via @Provides); this just keeps the annotation resolvable at compile time.
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}

// ---------------------------------------------------------------------------
// Unit test categories — the app/desktop convention, ported to :shared.
//
// Every test class declares exactly ONE bucket. The buckets partition the suite, so running all
// three covers everything; validateSharedTestCategories fails the build if a class declares zero
// (it would run in NO bucket) or more than one (it would run, and be counted, twice).
// Markers live in shared/src/test/kotlin/com/weatherwidget/test/category/.
//
// All :shared classes are currently Short — this module is pure JVM logic, ~0.9s for the whole
// suite. Medium/Long exist for symmetry with the other modules; empty bucket tasks are no-ops.
// ---------------------------------------------------------------------------
val sharedTestCategoryBuckets =
    mapOf(
        "Short" to "com.weatherwidget.test.category.ShortDuration",
        "Medium" to "com.weatherwidget.test.category.MediumDuration",
        "Long" to "com.weatherwidget.test.category.LongDuration",
    )

val validateSharedTestCategories by tasks.registering {
    group = "verification"
    description =
        "Verifies :shared unit test @Category usage: exactly one duration bucket per file, " +
        "no duplicates, only known markers."

    val testFiles =
        fileTree("$projectDir/src/test/kotlin") {
            include("**/*Test.kt")
            include("**/*Benchmark.kt")
        }

    inputs.files(testFiles)

    // Single source of truth: the marker whitelist derives from the bucket map, so a new bucket
    // added there is automatically legal in @Category and gets its own test task.
    val bucketNames = sharedTestCategoryBuckets.values.map { it.substringAfterLast('.') }.toSet()

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
                    appendLine("Shared unit test @Category violations:")
                    violations.forEach { violation -> appendLine(" - $violation") }
                },
            )
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(validateSharedTestCategories)
}

sharedTestCategoryBuckets.forEach { (bucketName, categoryClassName) ->
    listOf(false, true).forEach { forceExecution ->
        val taskSuffix = if (forceExecution) "Fresh" else ""
        tasks.register<Test>("test${bucketName}Shared$taskSuffix") {
            group = "verification"
            description =
                if (forceExecution) {
                    "Runs ${bucketName.lowercase()} shared unit tests without reusing previous results."
                } else {
                    "Runs ${bucketName.lowercase()} shared unit tests."
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

tasks.register("testByDurationShared") {
    group = "verification"
    description = "Runs all :shared unit test category buckets (short, medium, long)."
    dependsOn(sharedTestCategoryBuckets.keys.map { "test${it}Shared" })
}

tasks.register("testByDurationSharedFresh") {
    group = "verification"
    description =
        "Runs all :shared unit test category buckets (short, medium, long) without reusing results."
    dependsOn(sharedTestCategoryBuckets.keys.map { "test${it}SharedFresh" })
}
