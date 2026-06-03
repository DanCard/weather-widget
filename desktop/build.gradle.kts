import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    jvmToolchain(21)
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
