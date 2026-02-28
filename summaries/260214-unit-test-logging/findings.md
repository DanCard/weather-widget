# Findings - Unit Testing

## Current State
- **Framework:** JUnit 4
- **Location:** `app/src/test/java/com/weatherwidget`
- **Number of tests:** 100 tests across 14 test classes.
- **Gradle Task:** `:app:test`
- **Output:** Now verbose and compact.

## Key Discoveries & Solutions

### Gradle Verbosity
Using `testLogging` in `app/build.gradle.kts` with `events("passed", "skipped", "failed", "standardOut", "standardError")` provides excellent visibility into the test run. Using the explicit enum `org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL` is required in Kotlin DSL.

### Piping and Colors
Piping Gradle output through `grep` (to strip blank lines) disables color detection. This was fixed by:
1. Adding `--console=rich` to the Gradle command.
2. Using `grep --line-buffered` to maintain flow.
3. Capturing the exit code via `${PIPESTATUS[0]}` to ensure test failures are still detected.

### Environment Management
The project guidelines (`CLAUDE.md`) were updated to emphasize trusting the system `JAVA_HOME` rather than forcing a specific path, as the user's environment is already correctly configured for the Android Studio JBR.
