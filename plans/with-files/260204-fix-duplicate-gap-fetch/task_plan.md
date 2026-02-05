# Task Plan - Fix Duplicate Gap Data Fetch

## Goal
Decouple gap data fetching from specific API implementations (NWS/OpenMeteo) and ensure it only happens once per widget update cycle in a generic way.

## Phases
- [x] Phase 1: Analysis & Investigation
- [x] Phase 2: Refactoring Plan
- [x] Phase 3: Implementation
- [x] Phase 4: Verification

## Decisions
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-02-04 | Initial Plan | Fix duplicate gap fetch issue. |
| 2026-02-04 | Fetch Gap Once in getWeatherData | Best place to ensure it happens once after all real data is fetched. |
| 2026-02-04 | Use min(lastDates) for Gap Start | Ensures all potential gaps are filled for all APIs, while real data still takes precedence. |
| 2026-02-04 | Skip Gap Data in Snapshots | Gap data (climate normals) is static and does not belong in historical prediction records. |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| bash syntax error | 1 | Used `write_file` instead of `cat <<EOF` in `run_shell_command`. |