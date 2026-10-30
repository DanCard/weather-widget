# Testing Strategy

Use the lightest framework that can exercise the behavior. An integration test is defined by what it connects, not by its framework: a test that connects two or more components is an integration test whether it uses plain JUnit, Robolectric, or Android instrumentation.

## Choose the test layer

1. **Pure JVM first.** Put logic with no Android dependency in `test/` and test it with plain JUnit. Extract pure decisions from Android glue when that gives a meaningful test without reproducing platform behavior. Use `runTest` for coroutine tests.
2. **Robolectric for Android dependencies.** Use it for `Context`, `SharedPreferences`, Room, resources, receivers, and Android-facing coordination that needs the Android API but not real device rendering. In `:app`, extend `com.weatherwidget.test.RobolectricTest` when its defaults fit; it supplies `RobolectricTestRunner`, `@Config(sdk = [35])`, and `LongDuration`. Obtain context with `ApplicationProvider.getApplicationContext()`.
3. **MockK only where extraction or a real/fake collaborator is not practical.** MockK is already available in `:app` and `:desktop`. It is appropriate for otherwise-unavoidable Android/WorkManager boundaries, such as verifying `enqueueUniqueWork` policies in `UIUpdateReceiverTest` and `CurrentTempUpdateSchedulerTest`. Do not use mocks to simulate launcher sizing, canvas output, or other platform behavior whose real result is the thing under test.
4. **Instrumented tests for real Android behavior.** Use `app/src/androidTest/` for real `Canvas`/`Bitmap` rendering, `RemoteViews` and `performClick`, real `View.measure`/`layout`, and real SQLite migrations. Add a Robolectric counterpart when the same behavior can usefully be covered on the JVM; instrumentation remains the authority for actual rendering and platform integration.

A single-component Robolectric test can be a unit test; a multi-component plain-JVM test can be an integration test. Prefer descriptive backtick test names and cover both happy paths and edge cases.

## Shared Host contract tests

Shared runners own cross-platform rules; Android and desktop implement their `Host`/`Store` ports. Test both sides of that boundary:

- Put an abstract JUnit contract in `shared/src/testFixtures/kotlin/com/weatherwidget/shared/contract/` for every shared runner `Host` or `Store` port.
- Extend it in `app/src/test/` against the real Room implementation, normally with Robolectric and an in-memory test database.
- Extend the same contract in `desktop/src/test/` against the real desktop SQLite implementation and a temporary database.
- Categorize each concrete subclass in its own module. The abstract fixture itself is not a standalone test class.
- Keep runner-rule tests separate: fakes are useful there, but they do not prove that either platform's SQL implements the port correctly.

`desktop/src/test/kotlin/com/weatherwidget/desktop/HostContractCoverageTest.kt` enforces this arrangement. Its `PENDING` set is empty and may only shrink; adding a new shared port without both concrete contract suites fails the test. `ForecastPersistHostContract` and its `RoomForecastPersistHostContractTest` / `DesktopForecastPersistHostContractTest` subclasses show the pattern.

## Required categories

Every test class in **all three modules** must declare exactly one category. Validation runs before each module's test tasks and rejects missing, duplicate, multiple, or unknown categories. The validators scan `*Test.kt` and `*Benchmark.kt` in each module's test source tree.

Duration buckets use the class's measured wall time: **Short < 0.2 s, Medium 0.2–2 s, Long ≥ 2 s**. App `ShortDuration` tests must be pure JVM tests without Robolectric; move Android-dependent tests to Medium or Long as measured.

| Module | Allowed categories | Markers |
|---|---|---|
| `:app` | `ShortDuration`, `MediumDuration`, `LongDuration`, **or `Localization`** | `app/src/test/java/com/weatherwidget/test/category/` |
| `:desktop` | `ShortDuration`, `MediumDuration`, or `LongDuration` | `desktop/src/test/kotlin/com/weatherwidget/test/category/` |
| `:shared` | `ShortDuration`, `MediumDuration`, or `LongDuration` | `shared/src/test/kotlin/com/weatherwidget/test/category/` |

`Localization` is a topic bucket, not a fourth duration. An app localization test declares it **instead of**, never alongside, a duration category. All app category tasks, including `testByDurationDebugUnitTest`, include Localization. The category names share the package `com.weatherwidget.test.category` in every module.

Useful Gradle tasks when not using the scripts:

| Module | One category | All categories |
|---|---|---|
| `:app` | `./gradlew :app:testShortDebugUnitTest` (or `Medium`, `Long`, `Localization`) | `./gradlew :app:testByDurationDebugUnitTest` |
| `:desktop` | `./gradlew :desktop:testShortDesktop` | `./gradlew :desktop:testByDurationDesktop` |
| `:shared` | `./gradlew :shared:testShortShared` | `./gradlew :shared:testByDurationShared` |

The Medium/Long tasks in `:shared` are valid no-ops while that module has only Short tests. Whole-module `:shared:test` and `:desktop:test` also run category validation.

## Canonical test commands

Run the full gate from the repository root:

```bash
./scripts/staggered-tests.sh
./scripts/staggered-tests.sh --sequential
./scripts/staggered-tests.sh --install
./scripts/staggered-tests.sh --emulator-args "-c com.weatherwidget.widget.SomeTest"
```

`staggered-tests.sh` currently works as follows:

- It runs all app categories plus the `:shared` and `:desktop` suites. If no emulator is running, it boots one beside the unit tests in either mode, alternating the installed `Generic_Foldable_API36` and `Medium_Phone_API_36` targets.
- By default, the unit run also builds the app and androidTest APKs. Emulator tests start during Long's tail only after both APKs exist and Short, Medium, and Localization have reported without a failure. The emulator phase therefore never overlaps the full Short/Medium/Long/Localization load.
- A Long failure can happen **after emulator tests have already started or finished**. A Short, Medium, or Localization failure prevents the early start, and emulator tests are skipped after a failed unit run that never started them. Do not assume “unit failed” means no emulator phase ran.
- `--sequential` restores the old ordering: instrumentation starts only after all unit tests pass.
- Connected emulators run in parallel after one APK build. Transient emulator/runner disconnects are retried up to three attempts.
- `--install` defers `installDebug` until both phases pass. Do not run `unit-tests.sh --install` beside instrumentation: installation broadcasts to every connected device and can force-stop the test process.
- Logs are in `logs/staggered-tests/`, including the readable `summary-<timestamp>.log`; files older than 14 days are pruned.

For individual layers:

```bash
./scripts/unit-tests.sh                    # all app categories + shared + desktop (cached by default)
./scripts/unit-tests.sh Short              # only this app category
./scripts/unit-tests.sh Localization       # only app localization tests
./scripts/unit-tests.sh --cached           # default: allow up-to-date test tasks
./scripts/unit-tests.sh --fresh            # force app category tasks to execute
./scripts/emulator-tests.sh                # all instrumented tests on connected emulators (cached by default)
./scripts/emulator-tests.sh --fresh        # force tests to execute on emulator (bypass cache)
./scripts/emulator-tests.sh -c com.weatherwidget.util.RainAnalyzerIntegrationTest
./scripts/emulator-tests.sh -e Medium_Phone_API_36 -c com.weatherwidget.widget.SomeTest
./scripts/staggered-tests.sh --fresh       # force fresh unit and emulator test execution
```

With no arguments, `unit-tests.sh` includes all four app categories and runs `:shared:test` and `:desktop:test`. Selecting one or more categories runs only those app categories. Both `unit-tests.sh` and `emulator-tests.sh` (and `staggered-tests.sh`) report cached results when code and test inputs are unchanged; use `--fresh` to force execution. Emulator logs go to `logs/emulator-tests/`; emulators remain running after the script exits.

Test runs are serialized per checkout by `scripts/lib/test-lock.sh`, because concurrent Gradle builds of one project corrupt shared `build/` outputs. A second `unit-tests.sh`, `emulator-tests.sh`, or `staggered-tests.sh` queues for up to 6 minutes (`WW_TEST_LOCK_WAIT`), then exits 75, and a run holding the lock has its process tree stopped after 5 minutes (`WW_TEST_TIMEOUT`). `emulator-tests.sh -d` raises its own limit; raise `WW_TEST_TIMEOUT` for a cold build or a long `staggered-tests.sh`. Emulator cache hits and `--boot-only` skip the lock. Direct `./gradlew` calls and other scripts do not take it.

An emulator pass is cached only if the source fingerprint at the end of the run matches the one taken before the build, so editing during a run costs a rerun rather than recording a pass for untested code.

Always use `emulator-tests.sh` for instrumentation. **Never run `./gradlew connectedDebugAndroidTest` directly**: it targets **all** connected devices, including physical phones, and can remove their widgets.

`gradle.properties` sets `android.injected.androidTest.leaveApksInstalledAfterRun=true`, and `emulator-tests.sh` preserves APKs by default. Keep that protection: uninstalling the app removes launcher widget instances. See [operations.md](operations.md) for runtime inspection and device-safety procedures.
