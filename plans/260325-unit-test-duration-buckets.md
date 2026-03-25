# Add Explicit Unit-Test Duration Buckets

## Summary
Split `app/src/test` unit tests into explicit `Short`, `Medium`, and `Long` buckets using JUnit 4 categories, and expose separate Gradle test tasks for each bucket.

This is feasible with low-to-moderate effort in this repo because the test suite is already all JUnit 4, but there is no existing categorization. The main caveat is that sub-10-second feedback is realistic for the `Short` bucket only when compilation is already up-to-date; a cold run is dominated by Kotlin/KSP/Android test setup, not test execution.

## Key Changes
- Add three JUnit 4 marker interfaces or category annotations for `ShortTest`, `MediumTest`, and `LongTest`.
- Annotate every unit-test class in `app/src/test/java` with exactly one duration bucket.
- Treat the initial classification as intentional/manual, not inferred:
  - `Short`: pure JVM logic tests with minimal setup and no Robolectric/Room-heavy integration behavior.
  - `Medium`: moderate-cost tests such as lighter Robolectric or in-memory DB tests that are still useful for regular local runs.
  - `Long`: the heaviest Robolectric/integration-like tests and the existing benchmark-style test.
- Add separate Gradle `Test` tasks in `app/build.gradle.kts` that reuse the debug unit-test compiled output and filter by category:
  - one task per bucket
  - an optional aggregate task that depends on all three
- Keep the existing `testDebugUnitTest` behavior unchanged so current workflows still work.
- Add a small wrapper script or documented command set for parallel local execution:
  - recommended default: run `short`, `medium`, and `long` as separate Gradle invocations in parallel
  - keep `short` runnable by itself for fast feedback
- Move `TemperaturePipelineBenchmark` into `Long` by default so it never pollutes the fast path.

## Interfaces / Commands
- New test category markers become the public convention for all future unit tests.
- New Gradle commands should be added and documented, for example:
  - `./gradlew :app:testShortDebugUnitTest`
  - `./gradlew :app:testMediumDebugUnitTest`
  - `./gradlew :app:testLongDebugUnitTest`
- Add one documented parallel entrypoint, either as a script or command snippet, that launches those three in parallel and reports combined status.

## Test Plan
- Verify each new task runs only its assigned category and excludes the others.
- Verify every unit-test class is assigned exactly one bucket; fail fast if an uncategorized class appears.
- Verify `testDebugUnitTest` still runs the full suite unchanged.
- Verify the `short` bucket is materially faster than the full suite on an incremental run.
- Verify parallel execution returns a non-zero exit when any bucket fails and preserves readable failure output.

## Assumptions
- Use manual category assignment, not heuristic or timing-generated assignment.
- Expose separate Gradle tasks and run them in parallel via separate processes, not by relying on Gradle to parallelize multiple `Test` tasks inside this single-module Android project.
- Optimize for fast incremental feedback; do not promise cold-start under 10 seconds because the current cold path includes substantial compile/KSP work.
- Initial classification should bias conservative:
  - if a test uses Robolectric, ActivityScenario, or heavier DB setup, start it in `Medium` or `Long`
  - only keep obviously cheap pure-logic tests in `Short`
