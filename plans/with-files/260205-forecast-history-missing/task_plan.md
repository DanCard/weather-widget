# Task Plan - Diagnose Missing Forecast History

## Goal
Diagnose why forecast history is missing on all devices and propose a fix.

## Phases
- [x] Phase 1: Context Gathering & Codebase Investigation (Understand how history is saved and queried)
- [x] Phase 2: Diagnostic Analysis (Identify why history is not appearing)
- [x] Phase 3: Implementation & Verification (Apply fixes and verify)
- [x] Phase 4: Finalized (Awaiting final user confirmation)

## Decisions
| Decision | Rationale |
|----------|-----------|
| Increase tolerance to 0.1 | Accounts for 7 miles of jitter, which covers most mobile location shifts while still being local. |
| Add date to DayData | Most robust way to ensure UI and Click Handler are in sync. |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| | | |
