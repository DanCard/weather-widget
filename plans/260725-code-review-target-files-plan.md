# Code Review & Refactoring Target Plan

**Date**: July 25, 2026  
**Goal**: Perform a file-by-file code review of the highest-complexity and largest source files in the codebase, identifying and fixing code smells, high cyclomatic complexity, dead code, or redundant logic. Run full tests after each file is reviewed and updated, and stage changes for commit.

---

## 1. Prioritized Review Targets

The target files are selected based on static analysis metrics (cyclomatic complexity $> 50$ and line counts $> 900$ lines):

### Target 1: `ForecastRepository.kt`
- **Location**: [ForecastRepository.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt)
- **Metrics**: 1,396 lines | `snapshotDisplayedRainChance` function has **Cyclomatic Complexity 94** (816 lines).
- **Review Focus**:
  - Break down `snapshotDisplayedRainChance` into clean, testable sub-functions (SRP).
  - Simplify nested `when`/`if` branching and condition evaluation.
  - Verify DB transaction boundaries and error handling.

### Target 2: `WidgetStateManager.kt`
- **Location**: [WidgetStateManager.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt)
- **Metrics**: 992 lines | `logEvent` function has **Cyclomatic Complexity 67** (914 lines).
- **Review Focus**:
  - Refactor giant `logEvent` method into modular log dispatchers.
  - Enforce log level rules: ensure high-frequency traces use `Log.v` (ephemeral) and sparse diagnostics use `Log.d`.
  - Validate state storage/retrieval concurrency.

### Target 3: `DesktopWeatherService.kt`
- **Location**: [DesktopWeatherService.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt)
- **Metrics**: `fetchForecast` function has **Cyclomatic Complexity 56** (375 lines).
- **Review Focus**:
  - Refactor `fetchForecast` flow to isolate per-source API calls and fallbacks.
  - Audit Desktop HTTP client lifecycle and exception recovery.

### Target 4: `DailyForecastGraphRenderer.kt`
- **Location**: [DailyForecastGraphRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt)
- **Metrics**: 1,396 lines.
- **Review Focus**:
  - Audit Canvas rendering allocations inside `onDraw`/draw loops.
  - Simplify label placement collision detection.

### Target 5: `WeatherWidgetProvider.kt`
- **Location**: [WeatherWidgetProvider.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt)
- **Metrics**: 1,256 lines.
- **Review Focus**:
  - Audit BroadcastReceiver `goAsync()` handling.
  - Verify intent action routing and state persistence.

---

## 2. Review Protocol Per File

For each file in the target list, we execute the following sequence:

1. **Detailed Code Audit**:
   - Read the target file in detail.
   - Identify code smells, excessive complexity, deep nesting, or duplication.
2. **Refactoring & Code Improvements**:
   - Extract helper functions, simplify branching, improve variable naming, ensure proper logging levels.
3. **Automated Testing & Verification**:
   - Run unit tests across all duration categories (`./scripts/unit-tests.sh` or `./gradlew test`).
   - Re-run `./scripts/analyze_complexity.py` to measure complexity reduction.
4. **Staging & Commit Preparation**:
   - Stage modified files (`git add`).
   - Present detailed commit message summary (noting the Global Personal Memory rule requiring user confirmation for final commit execution).
