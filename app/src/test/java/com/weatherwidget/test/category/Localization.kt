package com.weatherwidget.test.category

/**
 * Topic category for localization / language tests, orthogonal to the duration buckets:
 * every test class still declares exactly one duration category, and topic markers ride
 * along in the SAME annotation (JUnit's @Category is not repeatable), e.g.
 * `@Category(ShortDuration::class, Localization::class)`.
 *
 * Run the slice with `./scripts/unit-tests.sh Localization` or
 * `./gradlew :app:testLocalizationDebugUnitTest`.
 */
interface Localization
