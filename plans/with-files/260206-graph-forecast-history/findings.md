# Findings & Decisions

## Requirements
- Add two display modes when viewing forecast history for a tapped day.
- Mode 1: Evolution view.
- Mode 2: Error-over-time view.
- Include a button to toggle between modes.
- Restrict data to one API: the active/requested API.

## Research Findings
- `ForecastHistoryActivity` already receives `EXTRA_SOURCE` and normalizes it (`NWS` / `OPEN_METEO`).
- Data load already filters snapshots by `requestedSource` when provided.
- Current rendering is split into high and low graphs via `ForecastEvolutionRenderer.renderHighGraph` and `renderLowGraph`.
- Existing legend has NWS/Open-Meteo/Actual groups and can be reused with mode-aware label updates.
- Compile check passed with `./gradlew :app:compileDebugKotlin` after changes.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Reuse existing activity filtering for API-specific behavior | Avoids adding new query complexity and already aligns with widget intent extras |
| Add mode toggle in history layout | Keeps interaction local and discoverable |
| Implement error mode in renderer instead of a separate screen | Keeps user flow fast after bar tap |
| Keep error mode API-specific and switch legend label to `Zero Error` | Reinforces active-source context and improves chart interpretation |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Prior turn was interrupted after layout patch | Confirmed current modified file via `git status`; resumed with checkpoint docs |

## Resources
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`
- `app/src/main/java/com/weatherwidget/widget/ForecastEvolutionRenderer.kt`
- `app/src/main/res/layout/activity_forecast_history.xml`

## Visual/Browser Findings
- None (local code exploration only).

## Evidence-First Debug (2026-02-06 late session)
- DB evidence (Samsung latest backup `backups/20260206_212348_sm-f936u1_RFCT71FR9NT/databases/weather_database`): `scripts/query/nws_today_forecast_history_samsung.sh` showed 18 NWS snapshots for `2026-02-06` spread across `00:39` to `20:33` local time.
- DB evidence (direct query): both `2026-02-05` and `2026-02-06` target dates have multiple snapshots over several hours.
- Logs evidence: `app_logs` had no relevant forecast-history diagnostic rows; runtime logcat showed activity launch/teardown but no renderer diagnostics.
- Inference: prior implementation rendered X-axis by lead-day buckets (`7d`, `6d`, ...) rather than issue-time timeline, causing mismatch with expected timeline view.
- Action taken: patched `ForecastEvolutionRenderer` to map X-axis to `fetchedAt` timestamps and label timeline ticks in both evolution and error modes.
