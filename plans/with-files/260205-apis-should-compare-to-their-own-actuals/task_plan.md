# Task Plan: Fix Forecast History Source-Matching in Compare Graph

## Goal
Ensure tapping a daily forecast history bar opens a comparison graph that uses matching source data (`NWS forecast` vs `NWS actual`, `Open-Meteo forecast` vs `Open-Meteo actual`).

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
- [x] Identify files involved in history click flow
- [x] Document decisions with rationale
- **Status:** complete

### Phase 3: Implementation
- [x] Patch source-selection logic for compare graph input data
- [x] Keep behavior unchanged for unrelated widget/history paths
- [x] Add or update targeted tests
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Run targeted unit tests
- [x] Validate source-matching behavior through existing logic/tests
- [x] Record results in progress.md
- **Status:** complete

### Phase 5: Delivery
- [x] Summarize root cause and fix
- [x] Provide changed files and verification status
- [x] Note any follow-up testing gaps
- **Status:** complete

## Key Questions
1. Where is the source chosen when navigating from daily forecast history bar to compare graph?
2. Where are "actual" values loaded, and is the selected source propagated correctly?
3. What is the narrowest fix that preserves existing behavior while enforcing source parity?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use minimal targeted patch in navigation/data-loading path | Reduces regression risk in widget rendering code paths |
| Add/adjust focused tests near fixed logic | Prevents recurrence and documents intended source mapping |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|

## Notes
- Re-read this plan before major code decisions.
- Log failures and test outcomes in progress.md.
