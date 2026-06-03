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

compose.desktop {
    application {
        mainClass = "com.weatherwidget.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "weather-widget-desktop"
            packageVersion = "1.0.0"
        }
    }
}
