# Task Plan: Improve single-point forecast history graph behavior

## Goal
Fix the forecast-vs-actual history graph so it renders cleanly when only one forecast-history data point exists after tapping a daily-history bar.

## Current Phase
Phase 5

## Phases
### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Identify constraints and requirements
- [x] Document findings in findings.md
- **Status:** complete

### Phase 2: Planning & Structure
- [x] Define technical approach
- [x] Identify renderer + click-path files
- [x] Document decisions with rationale
- **Status:** complete

### Phase 3: Implementation
- [x] Update renderer logic for single-point history datasets
- [x] Keep behavior correct for multi-point datasets
- [x] Preserve style/labels and avoid regressions
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Run relevant unit tests
- [x] Validate build/test task succeeds
- [x] Document results in progress.md
- **Status:** complete

### Phase 5: Delivery
- [x] Review touched files
- [x] Summarize behavior change and rationale
- [ ] Deliver outcome to user
- **Status:** in_progress

## Key Questions
1. Which renderer draws the forecast-vs-actual graph shown after history-bar click?
2. How should x-coordinate mapping behave when there is exactly one data point?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use planning-with-files workflow for this fix | User explicitly requested this skill and task requires multi-step exploration/edit/test |
| Patch only `ForecastEvolutionRenderer` | Root cause and fix are localized in X-axis coordinate math |
| Center single-day datasets at graph midpoint | Prevents left-edge clustering when only one forecast history value exists |
| Keep multi-day mapping unchanged | Avoids behavior regressions in normal multi-point histories |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Gradle wrapper lockfile permission denied in sandbox | 1 | Reran Gradle command with escalated permissions |
| Invalid Gradle task/option pairing (`:app:test` with `--tests`) | 1 | Switched to `:app:testDebugUnitTest --tests ...` |

## Notes
- Preserve existing behavior for 2+ points.
- Keep fix minimal and localized in renderer logic.
