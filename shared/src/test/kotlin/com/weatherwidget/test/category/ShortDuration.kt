package com.weatherwidget.test.category

/**
 * `:shared` unit test duration buckets, mirroring the `:app` and `:desktop` markers. The package and
 * names are deliberately identical in all three modules so `@Category` lines read the same
 * everywhere; the three sets never share a classpath.
 *
 * Every `:shared` test class declares exactly ONE bucket; `validateSharedTestCategories` enforces it.
 * The buckets partition the suite, so running all three covers everything.
 *
 * Thresholds, by the class's own measured wall time:
 *  - [ShortDuration]  — under 0.2s. Pure functions, no I/O.
 *  - [MediumDuration] — 0.2s to 2s.
 *  - [LongDuration]   — 2s and up.
 *
 * As of the initial categorization all 74 `:shared` classes are [ShortDuration] — the whole suite is
 * 513 tests in ~0.9s, because this module is pure JVM logic with no Compose, socket, or emulator
 * work. [MediumDuration] and [LongDuration] are therefore empty today and exist for symmetry with
 * the other modules and for tests that grow slower later. An empty bucket task is a no-op, not a
 * failure.
 */
interface ShortDuration
