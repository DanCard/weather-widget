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
    implementation(compose.material3)

    // Desktop HTTP engine for the shared Ktor clients + JSON negotiation.
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Swing dispatcher so Compose UI coroutines have a Main dispatcher on the JVM.
    implementation(libs.coroutines.swing)

    testImplementation(libs.junit)
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
