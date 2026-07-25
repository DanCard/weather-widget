# Comprehensive Code Review Strategy & Execution Plan

**Target Repository**: Weather Widget (Android / Shared JVM / Compose Desktop)  
**Date**: July 25, 2026  
**Status**: Proposal / Draft

---

## 1. Executive Summary & Objectives

The goal of this Code Review Plan is to establish a rigorous, repeatable process for reviewing code additions and refactoring existing technical debt across `:app`, `:shared`, and `:desktop`.

### Key Objectives:
1. **Automate Mechanical Verification**: Use automated CI tools (Detekt, Android Lint, Lizard) to enforce code formatting, style, and complexity limits before human review begins.
2. **Prioritize High-Risk Code (Hotspots & God Objects)**: Target large files ($>500$ lines) and complex renderers/handlers for targeted reviews and refactoring.
3. **Enforce Architectural & Runtime Rules**: Guarantee adherence to critical project standards (e.g., proper log routing, `goAsync()` on receivers, never using `ExistingWorkPolicy.REPLACE` for active workers, `@Category` test requirements).
4. **Improve Review Efficiency**: Keep PR diffs small ($<300$ LOC) and adopt standardized review feedback classifications.

---

## 2. Phase 1: Automated Quality Gates (CI & Static Analysis)

Before any code is reviewed by a human, automated gates must validate the diff.

### 2.1 Static Analysis & Linting Configuration
- **Detekt (Kotlin Static Analysis)**:
  - Add/enforce rules for `ComplexMethod` (threshold: cyclomatic complexity $> 10$), `LargeClass` ($> 500$ lines), `LongParameterList` ($> 5$ args), `NestedBlockDepth` ($> 4$).
- **Lizard (Cyclomatic Complexity Audit)**:
  - Run periodic complexity scans across `:app/src`, `:shared/src`, and `:desktop/src`.
  ```bash
  # Check for overly complex functions across the codebase
  lizard -C 12 -L 120 app/src shared/src desktop/src
  ```
- **Android Lint & Compiler Warnings**:
  - Require zero new warnings on `assembleDebug` and `testByDuration*`.

---

## 3. Phase 2: Targeted Hotspot Analysis (Finding Large & Complex Code)

Code review focus is directed toward high-risk areas identified through file size, cyclomatic complexity, and edit churn.

### 3.1 Script for Identifying Review Targets
```bash
# 1. Find top 10 largest Kotlin source files
find app shared desktop -type f -name "*.kt" | xargs wc -l | sort -nr | head -n 10

# 2. Audit files with highest cyclomatic complexity using lizard
lizard -C 15 app/src/main shared/src/main desktop/src/main
```

### 3.2 Key Priority Areas in Weather Widget
- **Widget Renderers** (`HourlyTemperatureGraphRenderer.kt`, `DailyForecastGraphRenderer.kt`, `PrecipitationGraphRenderer.kt`):
  - Check for complex Canvas math, floating label collisions, font metric calculations, and allocation inside `onDraw`/render loops.
- **Repository & Data Layers** (`WeatherRepository.kt`, `ObservationRepository`):
  - Check for proper thread dispatching (`Dispatchers.IO`), DB transaction scoping, and Room entity mapping.
- **Background Workers & Receivers** (`WeatherWidgetWorker.kt`, `UIUpdateReceiver.kt`, `ScreenOnReceiver.kt`):
  - Audit WorkManager enqueue policies: ensure `ExistingWorkPolicy.KEEP` or `APPEND_OR_REPLACE` is used (NEVER `REPLACE` on running workers to prevent native ART crashes).

---

## 4. Phase 3: Structured Code Review Checklist

When conducting a human code review, evaluate changes against this standardized checklist:

### A. Architecture & Design
- [ ] **Single Responsibility Principle**: Does each class/function have a single clear purpose?
- [ ] **Module Boundaries**: Is JVM-only weather/API logic placed in `:shared`? Are Android context/RemoteViews dependencies strictly kept in `:app`?
- [ ] **State Preservation**: Are RemoteViews updates non-blocking and using proper `goAsync()` call chains?

### B. Correctness & Error Handling
- [ ] **Exception Handling**: Are network/database failures caught gracefully without crashing or silently swallowing errors?
- [ ] **Null Safety**: Are nullable types (`highTemp`, `lowTemp`, API responses) safely unwrapped without risk of `NullPointerException`?
- [ ] **WorkManager Safety**: Are work requests enqueued safely without triggering `ExistingWorkPolicy.REPLACE` on active unique workers?

### C. Logging & Persistence Rules
- [ ] **Ephemeral vs. DB Log Separation**:
  - High-frequency per-frame/tick/poll events use `Log.v(...)` (dropped at persistence boundary).
  - Sparse, queryable events use `Log.d(...)` or `Log.i(...)` (persisted to `app_logs`).
- [ ] **No Debug Cleanup Deletions**: Do not delete useful debug logs after verifying a fix.

### D. Performance & Resource Allocation
- [ ] **Allocations in Hot Loops**: Are Objects (e.g. `Paint`, `Path`, `RectF`) allocated outside render/draw loops?
- [ ] **Database & I/O**: Are DB reads/writes executed off the main thread?

### E. Test Coverage & Test Standards
- [ ] **Class Categorization**: Does every new test class declare a `@Category` marker (`Short`, `Medium`, or `Long`) in all modules (`:app`, `:shared`, `:desktop`)?
- [ ] **Framework Preference**: Are pure logic tests written as JVM unit tests, resource/Context tests using Robolectric, and only visual/RemoteViews tests written as instrumented (`androidTest/`)?
- [ ] **Descriptive Naming**: Are test methods named descriptively using backticks (e.g. `` `getInterpolatedTemperature returns null for empty list` ``)?

---

## 5. Phase 4: Code Review Feedback Standards

To ensure constructive communication during code reviews, format review comments using standard prefixes:

| Category | Prefix | Action Required |
|---|---|---|
| **Blocker** | `[Blocker]` | Must be resolved before merge (bug, crash risk, severe architecture violation). |
| **Request** | `[Request]` | Change requested (missing test case, edge case handling, performance improvement). |
| **Suggestion** | `[Suggestion]` | Non-blocking recommendation for readability or idiomatic Kotlin. |
| **Nit** | `[Nit]` | Trivial cleanup (typo, minor naming preference). Optional. |
| **Question** | `[Question]` | Clarification request regarding intent or design decision. |

---

## 6. Phase 5: Verification & Execution Checklist

Before concluding a code review session or marking work complete, run full verification:

```bash
# 1. Run unit tests across all modules by duration category
./gradlew :shared:testShortShared :app:testByDurationDebugUnitTest :desktop:testByDurationDesktop

# 2. Run instrumented tests on active emulator (if UI/Widget changed)
./scripts/emulator-tests.sh

# 3. Check for Category validation failures
./gradlew validateUnitTestDurations
```

---

## 7. Next Steps & Recommended Actions

1. Review and approve this plan file in `plans/260725-code-review-plan.md`.
2. Run the complexity script to identify the top 5 refactoring targets in the project.
3. Integrate `lizard` or `detekt` into local development scripts.
