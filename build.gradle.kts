plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // Declared here (not applied) so :app can apply them conditionally when google-services.json exists.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    // Copy-paste detector (PMD CPD). Applied at the root so a single `./gradlew cpdCheck` scans all
    // modules. Report-only — see config below.
    alias(libs.plugins.cpd)
}

// ---------------------------------------------------------------------------------------------
// Copy-paste detection (de.aaschmid.cpd → PMD CPD with Kotlin support).
//
// Surfaces duplicated Kotlin blocks across :app, :desktop, and :shared so duplication is visible
// rather than caught only in review. Intentionally REPORT-ONLY (ignoreFailures = true): it never
// fails a build. Promoting it to a CI gate is a deliberate follow-up once the baseline is clean.
//
// Run:    ./gradlew cpdCheck
// Report: build/reports/cpd/cpd.xml  and  build/reports/cpd/cpd.txt
// ---------------------------------------------------------------------------------------------
cpd {
    // Let the plugin pick its matching PMD (CPD) version; pinning an older PMD breaks the
    // report renderer the plugin worker expects. PMD's CPD ships a Kotlin tokenizer.
    language = "kotlin"
}

tasks.named<de.aaschmid.gradle.plugins.cpd.Cpd>("cpdCheck") {
    // Tuned so the renderer-sized duplicates surface without flagging trivial boilerplate.
    minimumTokenCount = 70
    ignoreFailures = true
    language = "kotlin"
    // Production Kotlin across all three modules; tests excluded (fixtures legitimately repeat).
    source = files(
        "app/src/main",
        "desktop/src/main",
        "shared/src/main",
    ).asFileTree.matching { include("**/*.kt") }
    reports {
        xml.required.set(true)
        text.required.set(true)
    }
}
