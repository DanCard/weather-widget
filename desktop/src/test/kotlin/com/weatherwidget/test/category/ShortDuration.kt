package com.weatherwidget.test.category

/**
 * Desktop unit test duration buckets, mirroring the app module's
 * `com.weatherwidget.test.category` markers. The package and names are deliberately identical so
 * `@Category` lines read the same in both modules (and so a future move into a shared test-fixtures
 * artifact would not touch a single import). The two sets never share a classpath.
 *
 * Every `:desktop` test class declares exactly ONE bucket; `validateDesktopTestCategories` enforces
 * it. The buckets partition the suite, so running all three covers everything.
 *
 * Thresholds, by the class's own measured wall time:
 *  - [ShortDuration]  — under 0.2s. Pure functions, no I/O.
 *  - [MediumDuration] — 0.2s to 2s. Real SQLite, ktor MockEngine, lighter Compose trees.
 *  - [LongDuration]   — 2s and up. Full Compose UI harnesses and real socket/IPC startup.
 *
 * A class that drifts across a boundary should be re-bucketed, but being one bucket off is harmless:
 * it changes only which task runs it, never whether it runs.
 */
interface ShortDuration
