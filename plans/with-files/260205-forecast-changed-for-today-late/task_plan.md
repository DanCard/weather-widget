# Task Plan: Investigate NWS Today Forecast Behavior and Add Durable Diagnostics

## Goal
Establish source-of-truth evidence for NWS today-value behavior and persist enough diagnostics to determine, after the fact, whether today's value came from forecast periods or observation-derived data.

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
- [x] Identify likely code path for NWS day mapping and mixed-source behavior
- [x] Document decisions with rationale
- **Status:** complete

### Phase 3: Implementation
- [x] Trace NWS parsing and merge logic in `fetchFromNws`
- [x] Add persistent NWS diagnostics in `app_logs`
- [x] Add provenance and transition logging for today's values
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Build-verify code changes compile
- [x] Verify backup timeline confirms first switch to `68`
- [x] Document results in progress.md
- **Status:** complete

### Phase 5: Delivery
- [x] Summarize findings and confidence level
- [x] Provide query instructions for future incidents
- [x] Persist learning notes in repository
- **Status:** complete

## Key Questions
1. Did NWS return tomorrow's value and have we mislabeled it as today?
2. Is today's displayed value forecast-derived, observation-derived, or mixed?
3. Do we have durable logs to prove provenance on future incidents?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Add DB-persisted diagnostics in `app_logs` | Logcat is ephemeral and insufficient for forensic analysis |
| Track source of today's high/low separately | Mixed-source merges are the core ambiguity |
| Log transitions against prior same-day NWS snapshot | Needed to pinpoint exactly when and how values changed |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Host/network and ADB workflow constraints during live fetch attempts | 1 | Proceeded with backup DB analysis + persistent logging implementation |

## Notes
- Existing app behavior can mix observation high with forecast night low for `targetDate=today`.
- New `app_logs` tags now provide durable provenance and transition trails.
