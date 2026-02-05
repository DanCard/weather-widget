# Task Plan: Diagnose Missing Forward Arrow on Folding Phone

## Goal
Diagnose and fix the missing forward arrow on folding phones when navigating history, and ensure consistency between display and navigation logic with unit tests.

## Current Phase
Phase 4: Testing & Verification

## Phases

### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Explore codebase to identify arrow navigation logic
- [x] Analyze RemoteViews layout for arrows
- [x] Identify how arrow visibility is toggled (history vs forecast)
- [x] Document findings in findings.md
- **Status:** complete

### Phase 2: Planning & Structure
- [x] Define technical approach for fixing visibility
- [x] Create `NavigationUtils` to unify offset and navigation logic
- [x] Decide on testing strategy (Unit tests for `NavigationUtils`)
- [x] Document decisions with rationale
- **Status:** complete

### Phase 3: Implementation
- [x] Implement `NavigationUtils`
- [x] Refactor `WeatherWidgetProvider` to use `NavigationUtils`
- [x] Verify fix in code logic
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Create `NavigationUtilsTest.kt`
- [x] Verify fix with unit tests (especially for high `numColumns`)
- [ ] Document test results in progress.md
- **Status:** in_progress

### Phase 5: Delivery
- [ ] Final review of code changes
- [ ] Deliver fix proposal and explanation to user
- **Status:** pending

## Key Questions
1. Where is the logic that controls `GONE`/`VISIBLE` for the forward arrow? (Answered: `setupNavigationButtons`)
2. Why does hitting the "back" arrow (history) cause the "forward" arrow to disappear? (Answered: `maxOffset` calculation mismatch)
3. Is there something specific about the folding layout? (Answered: Yes, high `numColumns` triggers the bug)

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use planning-with-files | Requested by user for complex diagnosis |
| Teach and explain mode | Requested by user |
| Extract Navigation logic | To unify duplicated logic and allow unit testing. |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Compilation error (Unresolved reference) | 1 | Added missing import for `NavigationUtils` in `WeatherWidgetProvider.kt`. |

## Notes
- API is NWS.
- Issue confirmed via code analysis: mismatch between graph display range and navigation bound check for `numColumns >= 7`.
- `NavigationUtils` now provides a single source of truth for both display and navigation offsets.
