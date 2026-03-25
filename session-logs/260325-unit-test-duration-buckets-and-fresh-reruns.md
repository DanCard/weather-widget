# Session Log: Unit Test Duration Buckets And Fresh Reruns

## User Goal
1. Break unit tests into short, medium, and long duration buckets.
1. Run those buckets in parallel.
1. Get short-test feedback under roughly 10 seconds.
1. Avoid reusing cached test results when desired, but without paying unnecessary compile/KSP rebuild cost.

## Repo Context Gathered
- Unit tests live under `app/src/test/java`.
- The project uses JUnit 4, not JUnit 5.
- There was no pre-existing category/tag split for unit tests.
- `app/build.gradle.kts` already configured all `Test` tasks with:
  - `maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)`
  - verbose test logging
- Unit tests mixed together:
  - pure JVM logic tests
  - Room-backed tests
  - Robolectric tests
  - heavier integration-style tests
- Instrumented tests under `app/src/androidTest` were not part of this task.

## Initial Investigation
- Ran repo searches for:
  - `@Category`
  - `@Tag`
  - `includeCategories`
  - `excludeCategories`
  - `maxParallelForks`
  - existing custom test tasks
- Confirmed there was no current duration bucketing mechanism.
- Enumerated all unit test classes under `app/src/test`.
- Counted 86 unit-test classes.
- Counted 37 Robolectric-based classes.

## Performance Evidence Collected

### Full unit suite
- Ran `./gradlew :app:testDebugUnitTest --rerun-tasks --profile --console=plain`
- Observed:
  - full cold-ish rerun took about `1m 7s`
  - a large part of that time was not test execution
  - main costs included:
    - `kspDebugKotlin`
    - `compileDebugKotlin`
    - `kspDebugUnitTestKotlin`
    - `compileDebugUnitTestKotlin`

### Per-class timing inspection
- Parsed XML under `app/build/test-results/testDebugUnitTest`.
- Top slow classes included:
  - `DailyViewHandlerTest`
  - `WeatherObservationsActivityRobolectricTest`
  - `TemperatureGraphRendererActualsTest`
  - DAO tests (`WeatherDaoTest`, `HourlyForecastDaoTest`, `ForecastSnapshotDaoTest`, `ApiUsageDaoTest`)
  - `NwsMiddayOverrideTest`
  - `HistoryActivitySyncRoboTest`
  - `OpenMeteoIntegrationTest`
  - multiple temperature graph renderer tests
- Conclusion:
  - simple package-based splitting would be misleading
  - the fast path needed explicit curation

### Short bucket investigation
- Ran `./gradlew :app:testShortDebugUnitTest --console=plain`
- Incremental run completed quickly once compilation was already up to date.
- Re-ran `./gradlew :app:testShortDebugUnitTest --console=plain --profile --rerun-tasks`
- Confirmed the user’s complaint:
  - `--rerun-tasks` forced the whole Android/KSP compile graph again
  - short test execution itself was not the dominant cost
  - compile/setup dominated the total wall time

## Plan Decisions Made
- Use explicit manual JUnit 4 categories rather than heuristics or timing-generated lists.
- Keep the existing full-suite task intact.
- Add separate bucketed Gradle tasks:
  - `testShortDebugUnitTest`
  - `testMediumDebugUnitTest`
  - `testLongDebugUnitTest`
- Add a parallel convenience script.
- Add validation so every unit test class must declare exactly one duration category.
- Add separate `Fresh` variants later when the user pointed out that `--rerun-tasks` was too expensive.

## Files Added

### Test category markers
- `app/src/test/java/com/weatherwidget/test/category/ShortDuration.kt`
- `app/src/test/java/com/weatherwidget/test/category/MediumDuration.kt`
- `app/src/test/java/com/weatherwidget/test/category/LongDuration.kt`

### Parallel runner script
- `scripts/test-unit-by-duration.sh`

### Planning artifact
- `plans/260325-unit-test-duration-buckets.md`

## Files Modified

### Gradle wiring
- `app/build.gradle.kts`

### Unit test classes
- All 86 unit test classes under `app/src/test/java` were modified to add one explicit `@Category(...)` annotation and the required imports.

## Gradle Changes Implemented

### Category definitions
- Introduced three JUnit 4 category marker interfaces:
  - `ShortDuration`
  - `MediumDuration`
  - `LongDuration`

### Validation task
- Added `validateUnitTestDurations`
- Purpose:
  - scan all `*Test.kt` and `*Benchmark.kt` files under `app/src/test/java`
  - verify each file contains exactly one of:
    - `@Category(ShortDuration::class)`
    - `@Category(MediumDuration::class)`
    - `@Category(LongDuration::class)`
- Hooked this validation into all `Test` tasks via `tasks.withType<Test>().configureEach`.

### Duration-specific test tasks
- Registered:
  - `testShortDebugUnitTest`
  - `testMediumDebugUnitTest`
  - `testLongDebugUnitTest`
- Each task:
  - reuses the `testDebugUnitTest` classes/classpath
  - filters execution by JUnit category with `useJUnit { includeCategories(...) }`

### Aggregate task
- Added `testByDurationDebugUnitTest`
- Depends on:
  - `testShortDebugUnitTest`
  - `testMediumDebugUnitTest`
  - `testLongDebugUnitTest`

## Initial Implementation Bug Fixes

### Validation task bug
- First attempt left `categoryMatches` as a sequence-like pipeline.
- Gradle Kotlin DSL compilation failed on `.size`.
- Fixed by materializing to `toList()` before `distinct()`.

### Task registration timing bug
- First attempt looked up `testDebugUnitTest` too early during configuration.
- AGP had not yet created the task.
- Fixed by moving bucket task registration into `afterEvaluate`.

### Bulk annotation classification bug
- First classification script compared absolute paths against relative-path classification sets.
- Result:
  - all 86 tests were accidentally annotated as `ShortDuration`.
- Fixed by normalizing paths to repo-relative values and rerunning the annotation pass.

## Test Classification Outcome
- Final bucket counts:
  - `Short`: 34
  - `Medium`: 28
  - `Long`: 24

### Classification strategy
- `Long`
  - heaviest measured tests
  - DAO-heavy tests
  - heaviest graph/UI Robolectric tests
  - benchmark class
- `Medium`
  - integration-style tests
  - lighter Robolectric tests
  - tests valuable for local iteration but not ideal for the fastest feedback path
- `Short`
  - clearly cheaper pure logic / API parsing / policy tests

## Script Changes Implemented

### `scripts/test-unit-by-duration.sh`
- Added parallel execution of bucket tasks in separate background Gradle invocations.
- Default behavior:
  - run `Short`, `Medium`, and `Long`
- Supports passing specific buckets:
  - `./scripts/test-unit-by-duration.sh Short`
- Prints each bucket’s log after completion and preserves non-zero status if any bucket fails.

## Verification After Initial Bucket Implementation
- Ran `./gradlew :app:validateUnitTestDurations`
  - passed
- Ran `./gradlew :app:testShortDebugUnitTest`
  - passed
- Ran `./gradlew :app:testMediumDebugUnitTest`
  - passed
- Ran `./gradlew :app:testLongDebugUnitTest`
  - passed
- Ran `./scripts/test-unit-by-duration.sh`
  - passed

### Observed timings
- Warm incremental `testShortDebugUnitTest`:
  - around `945ms`
- First post-change run of `testShortDebugUnitTest` after compile/setup:
  - around `10s`
- Cold-ish rerun with `--rerun-tasks`:
  - around `31s`

## Follow-up Investigation Triggered By User
- User asked why short tests still took ~21 seconds.
- Investigated with:
  - `./gradlew :app:testShortDebugUnitTest --console=plain --dry-run`
  - `./gradlew :app:testShortDebugUnitTest --console=plain --profile --rerun-tasks`
- Findings:
  - `--rerun-tasks` invalidated the entire upstream graph
  - this forced:
    - `kspDebugKotlin`
    - `compileDebugKotlin`
    - `kspDebugUnitTestKotlin`
    - `compileDebugUnitTestKotlin`
  - the problem was not the short tests themselves
  - the problem was task invalidation scope

## What is the difference between the fresh and non fresh version of the tests?


### Non-Fresh tasks reuse prior test-task results when Gradle thinks nothing relevant changed.

  Examples:

  ./gradlew :app:testShortDebugUnitTest
  ./gradlew :app:testMediumDebugUnitTest
  ./gradlew :app:testLongDebugUnitTest

  If inputs and outputs are unchanged, Gradle can mark them UP-TO-DATE and skip executing the tests.

  Fresh tasks **always rerun the Test task** itself, even when Gradle would otherwise skip it.

  Examples:

  ./gradlew :app:testShortDebugUnitTestFresh
  ./gradlew :app:testMediumDebugUnitTestFresh
  ./gradlew :app:testLongDebugUnitTestFresh

  The important part is what they do not do:

  - they do not force kspDebugKotlin
  - they do not force compileDebugKotlin
  - they do not force compileDebugUnitTestKotlin

  So:

  - non-Fresh = fastest when you’re okay with Gradle saying “already up to date”
  - Fresh = rerun the tests without reusing old test results
  - --rerun-tasks = much broader; reruns tests and all upstream tasks, which is why it got slow

  In practice:

  - use non-Fresh for normal development
  - use Fresh when you want the tests to actually execute again
  - avoid --rerun-tasks unless you really want the whole build/test pipeline rerun

## Fresh Rerun Solution Implemented

### New Gradle tasks
- Added:
  - `testShortDebugUnitTestFresh`
  - `testMediumDebugUnitTestFresh`
  - `testLongDebugUnitTestFresh`
  - `testByDurationDebugUnitTestFresh`

### Behavior
- Fresh tasks still depend on compiled outputs.
- Fresh tasks do **not** force compile/KSP work to rerun if those tasks are already up to date.
- They do force the `Test` task itself to execute again via:
  - `outputs.upToDateWhen { false }`

### Why this matters
- `--rerun-tasks` is too broad for “rerun tests only”
- `Fresh` tasks provide the useful behavior:
  - rerun test execution
  - keep compile/KSP tasks up to date when possible

### Script update
- Updated `scripts/test-unit-by-duration.sh` to support:
  - `--fresh`
- Examples:
  - `./scripts/test-unit-by-duration.sh --fresh`
  - `./scripts/test-unit-by-duration.sh --fresh Short`

## Verification After Fresh Task Implementation
- Verified task registration:
  - `testShortDebugUnitTestFresh`
  - `testMediumDebugUnitTestFresh`
  - `testLongDebugUnitTestFresh`
  - `testByDurationDebugUnitTestFresh`
- Ran `./gradlew :app:testShortDebugUnitTestFresh --console=plain`
  - compile/KSP tasks stayed `UP-TO-DATE`
  - short tests reran
  - total run was about `3s`
- This confirmed the intended behavior:
  - rerun tests without paying the full 30-second rebuild cost

## Commands Added / Recommended

### Standard duration tasks
```bash
./gradlew :app:testShortDebugUnitTest
./gradlew :app:testMediumDebugUnitTest
./gradlew :app:testLongDebugUnitTest
./gradlew :app:testByDurationDebugUnitTest
```

### Fresh rerun variants
```bash
./gradlew :app:testShortDebugUnitTestFresh
./gradlew :app:testMediumDebugUnitTestFresh
./gradlew :app:testLongDebugUnitTestFresh
./gradlew :app:testByDurationDebugUnitTestFresh
```

### Parallel script
```bash
./scripts/test-unit-by-duration.sh
./scripts/test-unit-by-duration.sh Short
./scripts/test-unit-by-duration.sh --fresh
./scripts/test-unit-by-duration.sh --fresh Short
```

## Important Lessons
- In this Android project, “rerun tests” and “rerun all upstream tasks” are very different things.
- `--rerun-tasks` is the wrong tool when the real need is “execute the `Test` task again.”
- The short bucket can be fast, but only if compile/KSP work remains up to date.
- Manual categorization is more maintainable here than heuristics because several slow tests are not obviously slow from package or runner alone.

## Current State At End Of Session
- Duration-bucket infrastructure implemented.
- All unit tests explicitly categorized.
- Validation task enforced.
- Parallel script implemented.
- Fresh rerun tasks implemented to avoid unnecessary compile/KSP reruns.
- Verified:
  - validation
  - short/medium/long bucket tasks
  - fresh short task
  - wrapper script

## Remaining Follow-up Possibilities
- Improve bucket balance further using historical timing data.
- Reduce validation overhead by making `validateUnitTestDurations` incremental or moving it to a dedicated verification path rather than all `Test` tasks.
- Consider whether some current `Short` tests that still exercise Android resources should move to `Medium`.
- Consider whether a custom report of per-bucket timing should be added to the parallel script.
