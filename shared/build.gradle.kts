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
