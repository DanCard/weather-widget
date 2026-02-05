# Task Plan: Samsung NWS History Display Fix

## Goal
Diagnose and fix the issue where NWS API history data is not being displayed on the Samsung device.

## Current Phase
Phase 1: Requirements & Discovery

## Phases

### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Identify the Samsung device serial/identifier
- [x] Investigate the database state for the Samsung device (specifically weather_data for NWS source)
- [x] Check logs (logcat) from the Samsung device backup
- [x] Document findings in findings.md
- **Status:** complete

### Phase 2: Analysis & Root Cause Identification
- [x] Re-analyze the root cause (previous diagnosis was rejected by user)
- [x] Identify why NWS history is missing on Samsung: identified "leaky" merge logic and lack of rate-limit persistence.
- **Status:** complete

### Phase 3: Implementation
- [x] Develop a fix for the correct root cause (History-preserving merge + Parallel fetches)
- [x] Apply the fix to the codebase
- **Status:** complete

### Phase 4: Verification
- [x] Verify the fix via unit tests
- [x] Document test results in progress.md
- **Status:** complete

### Phase 5: Finalize
- [x] Review changes
- [x] Commit and push
- **Status:** complete

## Key Questions
1. Which device is the "Samsung device"? `sm-f936u1_RFCT71FR9NT`
2. Does the Samsung device have NWS observation data in its database? It did, then lost it due to a merge bug.
3. Are there errors in the logcat related to NWS observation fetching? None found, but logs showed spamming.
4. Is this related to the "8 API calls" for NWS observations? Yes, sequential calls were too slow.

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Database-backed audit log | Provides 7-day forensic trail that survives process kills. |
| Union-based merge logic | Prevents data loss when API returns partial results. |
| Coroutine Parallelism | Reduces NWS fetch time from ~200s to ~5s. |
| Persistent Rate Limit | Prevents bursts when OS restarts the process. |

## Key Questions
1. Which device is the "Samsung device"?
2. Does the Samsung device have NWS observation data in its database?
3. Are there errors in the logcat related to NWS observation fetching?
4. Is this related to the "8 API calls" for NWS observations mentioned in `analyze_fetches.py`?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use planning-with-files | Complex task involving multi-device investigation and potential code changes. |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Incorrect Diagnosis (Worker timeout/spam) | 1 | Reverted changes, waiting for user feedback |

## Notes
- The Samsung device is likely `sm-f936u1_RFCT71FR9NT` based on the file structure.
- NWS history usually comes from "Observations" (station data).