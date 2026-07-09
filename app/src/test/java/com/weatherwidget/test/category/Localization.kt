package com.weatherwidget.test.category

/**
 * Category bucket for localization / language tests — a peer of the duration buckets,
 * not an add-on: a localization test declares `@Category(Localization::class)` ONLY and
 * lives in no duration bucket. Every test class carries exactly one bucket marker
 * (enforced by the `validateUnitTestDurations` Gradle task).
 *
 * Runs as part of the default `./scripts/unit-tests.sh`, or alone via
 * `./scripts/unit-tests.sh Localization` / `./gradlew :app:testLocalizationDebugUnitTest`.
 */
interface Localization
