# Task Plan: Implement DailyViewHandler.kt Code Review Findings

## Goal
Apply all 12 code review findings to DailyViewHandler.kt to reduce duplication, improve readability, and fix correctness issues.

## Current Phase
Phase 1

## Phases

### Phase 1: Quick Wins — Dead Code & Unused Imports
- [ ] Remove unused imports (`ForecastHistoryActivity`, `SettingsActivity`, `Job`)
- [ ] Remove unused `todayStr` variable at line 174
- [ ] Remove redundant `DailyVisibilityManager.hideUnusedDailyViews` call for graph mode
- **Status:** pending

### Phase 2: DRY — Eliminate Duplication
- [ ] Extract `DailyRenderContext` construction before the `if (useGraph)` branch
- [ ] Remove duplicate header bind calls in `bindHeaderState` (first pass without scale)
- [ ] Refactor `updateTextMode` to accept `DailyRenderContext` instead of 18 params
- [ ] Inline thin wrapper methods (`setupGraphDayClickHandlers`, `setupGraphBottomDayClickHandlers`, `setupGraphZoneClickHandlers`) or call `DailyClickHandlerFactory` directly
- **Status:** pending

### Phase 3: Scoping & Correctness
- [ ] Replace fire-and-forget `CoroutineScope(Dispatchers.IO).launch` with `withContext(Dispatchers.IO)`
- [ ] Move `database`/`appLogDao` instantiation inside the graph branch (or into `DailyRenderContext`)
- [ ] Fix `headerDateFormatter` locale capture — use per-call formatter or document intentional behavior
- **Status:** pending

### Phase 4: Documentation — Magic Numbers
- [ ] Add comments explaining `GRAPH_ROW_THRESHOLD = 2.2f` and `CELL_HEIGHT_DP = 90`
- **Status:** pending

### Phase 5: Build & Test Verification
- [ ] Run `./gradlew assembleDebug` to verify compilation
- [ ] Run `./gradlew test` for unit tests
- **Status:** pending

## Key Questions
1. Should `DailyRenderContext` gain a `database` field, or should `loadTodaySourceObservations` receive it as a param?
2. Is `headerDateFormatter` locale capture intentional (e.g., consistency across a single widget lifecycle)?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Extract DailyRenderContext before branch | Both branches construct it identically; DRY |
| Keep thin wrappers vs inline | TBD — depends on call count and readability |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
|       |         |            |
