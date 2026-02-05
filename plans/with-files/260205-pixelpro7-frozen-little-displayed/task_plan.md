# Task Plan: Root Cause Instrumentation for Weather Widget Display Issue

## Goal
Instrument the Weather Widget with high-fidelity, persistent logging to capture the definitive root cause of the "Empty Database" state if it recurs on the Pixel 7 Pro.

## Current Phase
Phase 5: Verification & Monitoring (Complete)

## Phases

### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Gather device-specific information (Pixel 7 Pro context)
- [x] Inspect relevant code (Widget provider, RemoteViews, layouts)
- [x] Document findings in findings.md
- **Status:** complete

### Phase 2: Planning & Investigation
- [x] Propose "Preserve and Reset" plan
- [x] Execute `scripts/backup_databases.py` on Pixel 7 Pro
- [x] Analyze backup logs (`logcat.txt`)
- [x] Perform clean reinstall (Temporary workaround)
- **Status:** complete

### Phase 3: Forensic Analysis (Ongoing)
- [x] Identify symptom: **Empty Database state.**
- [x] Verify that even existing logs were missing in the "broken" state.
- **Status:** complete

### Phase 4: Instrumentation for Root Cause
- [x] Implement Persistent Lifecycle Logging in `AppLogDao`
- [x] Instrument `WeatherWidgetWorker` for failure forensics
- [x] Instrument `WeatherWidgetProvider` for rendering decisions
- [x] **Configure log rotation: Set retention to 72 hours (3 days).**
- **Status:** complete

### Phase 5: Verification & Monitoring
- [x] Verify logs are correctly written to the internal database (via build check)
- [ ] Monitor device logs for the next failure event
- **Status:** complete

## Key Questions
1. Why does the background sync stop triggering or fail silently?
2. Are there specific battery optimization settings on Pixel 7 Pro killing the WorkManager?
3. Does the database initialization ever fail (Room migration issues)?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Unresolved Status | Reinstall is a workaround; the underlying sync failure is still unknown. |
| DB-Backed Logging | Logcat is volatile; internal DB logs persist until we pull them. |
| 72-Hour Retention | User requested 3-day max-age for logs to optimize space while maintaining forensics. |

## Notes
- Do not mark as "Fixed." Mark as "Instrumented for Analysis."
- Focus on the `AppLogDao` as our primary forensic tool.