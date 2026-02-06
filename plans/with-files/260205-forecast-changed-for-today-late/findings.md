# Findings & Decisions

## Requirements
- Determine whether the disputed NWS `68` value was tomorrow's forecast mislabeled as today.
- Stop relying on ephemeral logcat for this class of issue.
- Add durable evidence to diagnose future occurrences from backups alone.

## Research Findings
- Backup timeline for `targetDate=2026-02-05` shows NWS transitioned from `74` to `68` on the evening of 2026-02-05.
- At the same fetch moment, `targetDate=2026-02-06` remained `69`, so `68` was not tomorrow's value in stored NWS snapshots.
- Current pipeline can produce mixed today values:
  - today's high from observation station data
  - today's low from NWS `Tonight` period
- Without provenance logging, this mixed-source behavior is easy to misinterpret as day mislabeling.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Persist `NWS_PERIOD_SUMMARY` in `app_logs` | Keep compact durable record of incoming period context |
| Persist `NWS_TODAY_SOURCE` in `app_logs` | Explicitly identify OBS vs FCST source for today's high/low |
| Persist `NWS_TODAY_TRANSITION` in `app_logs` | Capture exactly when today's values change vs prior snapshot |
| Add learning query doc in `learning/260205-sqllite3-logs.md` | Make incident analysis repeatable and fast |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Logcat and live-fetch constraints during investigation | Used backup DB evidence + implemented durable DB logging |
| Gradle sandbox limits for verification | Re-ran compile with escalated permissions |

## Resources
- `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`
- `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt`
- `learning/260205-sqllite3-logs.md`
- `backups/*/databases/weather_database`

## Visual/Browser Findings
- No image/browser resources used.
