# Integration Testing Strategy (2026-02-26)

## Overview
Brainstormed process for systematic code review and integration testing of the weather widget's critical data pipelines. The codebase has 34 unit tests and 25 instrumented tests using a "pure function extraction" philosophy (no mocking framework). The gap is in the **middle layer** — integration tests that exercise real data flows (API → Repository → DB → Widget) without needing a physical device.

---

## 1. Critical Data Pipelines

| Pipeline | Why It Matters |
|----------|---------------|
| **NWS multi-station fetch → merge → DB** | Most complex path; 3-step API, 5-station fallback, merge logic that must not overwrite actuals |
| **Forecast snapshot archival → accuracy calc** | Core feature; if snapshots save wrong, accuracy tracking is silently broken |
| **Hourly data → TemperatureInterpolator → display** | User-visible every refresh; source fallback chain is subtle |
| **WidgetIntentRouter → handler dispatch** | Single 41KB router handles ALL user interactions; recent rain query window bug lived here |
| **DB migrations (18 of them!)** | Silent data corruption risk; Room's `MigrationTestHelper` exists for exactly this |

---

## 2. Tiered Review Process

### Tier A — "Read & Audit" (no code changes)
- Walk each pipeline end-to-end in code, documenting the data flow
- Flag any assumptions that aren't tested (e.g., "merge never overwrites actuals" — is that tested?)
- Look for error paths that silently succeed (e.g., station fetch returns null → what happens downstream?)

### Tier B — "Add Integration Tests" (new test files)
- Write Room-based integration tests using an in-memory database — these run as unit tests (no device needed) with Robolectric
- Test the Repository → DAO → DB round-trip with real Room queries
- Test migration paths with `MigrationTestHelper`

### Tier C — "Scenario Tests" (full pipeline)
- Mock only the HTTP layer (ktor-client-mock) and let everything else be real
- Inject mock HTTP responses → Repository fetches → DB persists → verify DB state
- Catches issues like: "NWS returns unexpected JSON structure" or "merge logic drops a field"

---

## 3. Prioritized Concrete Test Ideas

1. **Repository merge safety** — Insert historical actuals, then run a fetch with newer forecast data, verify actuals are untouched
2. **Multi-station fallback** — Mock station 1 returning null, station 2 returning data, verify correct station used and `stationId` saved
3. **Rate limiter reset on failure** — Unit tests exist, but an integration test with real DB + mock HTTP would catch edge cases
4. **DB migration chain** — Test v1→v19 migration with sample data at each step (Room's `MigrationTestHelper`)
5. **Forecast snapshot timing** — Verify snapshot saves correctly near the 8pm cutoff boundary
6. **Rain query window consistency** — End-to-end: insert hourly data spanning 60h, route through `WidgetIntentRouter`, verify `RainAnalyzer` sees all of it

---

## 4. Process Workflow

```
For each pipeline:
  1. Code review: trace the data flow, note untested assumptions
  2. Write integration test skeleton (inputs → expected outputs)
  3. Implement with in-memory Room DB + ktor-client-mock
  4. Run as unit tests (fast, no emulator needed)
  5. Document findings in a review notes file
```

---

## 5. Anti-Patterns to Avoid

- Don't try to mock `RemoteViews` or `AppWidgetManager` — the current approach of testing the logic layer is correct
- Don't add a mocking framework for its own sake — the pure-function-extraction pattern is working well
- Don't test Room query syntax in isolation — test it through the Repository where the real logic lives
