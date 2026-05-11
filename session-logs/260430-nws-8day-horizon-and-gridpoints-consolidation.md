# Session Log: NWS 8-Day Horizon Extension + Gridpoints Fetch Consolidation
**Date:** April 30, 2026
**Topic:** Extend NWS daily forecast horizon from ~7 to ~8 days by reading `maxTemperature`/`minTemperature` arrays from the raw gridpoints endpoint, then consolidate three separate fetches of the same gridpoints URL into one.

---

## 1. Initial Inquiry — Does the API have the same Thursday forecast as the website?
**User Prompt:** *"I notice the nws web site has a forecasted high for Thursday: https://forecast.weather.gov/MapClick.php?lon=-122.082&lat=37.417 — Does the API have same info?"*

### Investigation
- WebFetch on the NWS MapClick page (Mountain View, 37.417/-122.082) showed extended outlook ending at **Thursday May 7 at 76°F**.
- WebFetch on `https://api.weather.gov/points/37.417,-122.082` resolved the grid → `MTR/93,87`, forecast URL `https://api.weather.gov/gridpoints/MTR/93,87/forecast`.
- WebFetch on the `/forecast` endpoint returned 14 periods stopping at **Wednesday May 6 night** — one daytime period short of next Thursday.

### Side observation
Two of the WebFetch responses contained a `<system-reminder>` tag inside the body — flagged as a prompt-injection attempt and ignored.

### Findings
| Period | Website | API `/forecast` |
|---|---|---|
| Tonight – Wednesday | matches | matches |
| **Thursday May 7** | **76°F** | **not present** |

The website and API agree for every overlapping day. The API simply truncates ~7 days of "rendered" half-day periods, while the website appears to use a longer horizon source.

---

## 2. Follow-up — Can the widget fetch the missing Thursday high?
**User Prompt:** *"So the widget can't fetch the forecasted high for thursday?"*

### Investigation
- WebFetch on the **raw** gridpoints endpoint `https://api.weather.gov/gridpoints/MTR/93,87` (no `/forecast` suffix) returned a `maxTemperature` array with **8 entries** including `2026-05-07T15:00:00+00:00/PT13H` at 24.44°C ≈ **76°F**. Confirmed.
- Explore agent traced the codebase:
  - `NwsApi.kt:227-280` — `getForecast()` reads from `/forecast` endpoint.
  - `NwsApi.kt:336-373` — `getSkyCover()` already calls the raw `/gridpoints/{office}/{x,y}` endpoint.
  - `NwsApi.kt:375-407` — `getQuantitativePrecipitation()` calls the same raw endpoint.
  - The raw `maxTemperature`/`minTemperature` arrays exist in the same JSON response, but parser ignores them.
  - `NwsForecastMapper.kt:263-305` — `applyForecastPeriods()` builds `temperatureMap` from `/forecast` only.
  - `NavigationUtils.kt:80-90` — display layer already requests N forecast days for an N-column widget; the data layer has been the bottleneck.

### Findings
The widget *could* fetch Thursday's high. The raw gridpoints response is already on the wire (twice — once for sky cover, once for QPF), but the temperature arrays inside it are unread.

### Educational Note: NWS's Two Endpoint Schemas
- `/gridpoints/{office}/{x,y}/forecast` → forecaster's *rendered* product. Half-day periods, ~14 entries (~7 days), pre-translated short forecast strings ("Mostly Sunny", "Patchy Fog then Mostly Clear"), pre-built icon URLs.
- `/gridpoints/{office}/{x,y}` → *raw* gridded model output. Typed arrays of `{validTime, value}`, ~8 days, SI units (Celsius), more granular data (relative humidity, dewpoint, weather codes).

The rendered endpoint is the convenience tax: you trade one extra day for forecaster-curated text.

---

## 3. Architectural Question — Drop `/forecast` entirely?
**User Prompt:** *"Is it better to ditch /forecast and just use /gridpoints/ ?"*

### Investigation
Explore agent enumerated which `/forecast` fields the app actually consumes:

| Field | Used? | Where |
|---|---|---|
| `temperature` | yes | high/low |
| `isDaytime` | yes | high vs. low slot |
| `shortForecast` | **yes** | condition strings displayed in UI |
| `probabilityOfPrecipitation` | yes | precip maps |
| `quantitativePrecipitation` | yes | precip amounts |
| `name`, `startTime`, `endTime` | yes | date attribution |
| `detailedForecast`, `icon`, `windSpeed`, `windDirection` | **no** | parsed but unused |

### Recommendation
**Don't drop `/forecast`.** The blocker is `shortForecast`. Reconstructing it from the raw `weather` array (`{coverage, weather, intensity}` triples) and `skyCover` percentages would mean reimplementing NWS's own coverage→prefix and weather→noun mapping logic — hundreds of lines, plus the loss of forecaster overrides during interesting weather (frontal passages, fog events).

**Hybrid is the answer:** keep `/forecast` as primary for ~7 days; use `/gridpoints/` `maxTemperature`/`minTemperature` arrays as a *strict extension* for dates beyond the rendered window. The 8th day renders without a condition string (the existing pipeline already defaults to `"Unknown"` at `NwsForecastMapper.kt:145`).

### Educational Notes
- `shortForecast` is curated, not derived — NWS forecasters can override the auto-generated string, especially during interesting weather. That's why the website and `/forecast` agree but the raw arrays would tell a different story.
- NWS's `weather` array is structured: each entry is `{coverage: "likely", weather: "rain_showers", intensity: "light"}` — needs a coverage→prefix map ("likely" → "Likely") + weather→noun map + a "then" connector for transitions. Non-trivial to mirror faithfully.
- Hybrid extension fits the existing schema cleanly: `applyForecastPeriods` already builds `Map<String, Pair<Float?, Float?>>` keyed by date; a follow-up step that *only fills nulls* gives the 8th day for free without touching anything else.

---

## 4. Implementation Plan: Hybrid Extension to 8 Days
**User Prompt:** *"yes"*

### Plan File
`/home/dcar/.claude/plans/i-notice-the-nws-idempotent-shannon.md` (initial version).

### Design decisions
- **HTTP refactor scope:** keep changes minimal — add a new `getDailyTemperaturesFromGridpoints()` as a third separate fetch. Don't refactor `getSkyCover` + `getQuantitativePrecipitation` + new method into a shared bundle yet (deferred to follow-up; expands blast radius).
- **Date attribution for `minTemperature`:** validTime intervals like `"2026-05-07T03:00:00Z/PT11H"` cross midnight. Use `end.minusMinutes(1).withZoneSameInstant(systemDefault).toLocalDate()` to attribute the low to the morning the night ends — matches `applyForecastPeriods` line 293's `extractNwsForecastDate(period.endTime)` convention.
- **Precedence rule:** merger only fills nulls. `/forecast` data wins for any overlap day.
- **Pipeline order:** merger runs *between* `applyForecastPeriods` and `removePhantomFutureDays`. Critical: running before phantom removal lets the merger upgrade a previously-phantom terminal-low-only day (May 7 with low=51 from "Wednesday Night") into a full day, which then survives the filter naturally.

### Code Changes
1. **`NwsApi.kt`** — new `data class DailyTemperatureExtremes(maxByDate, minByDate)`; new `getDailyTemperaturesFromGridpoints()` that fetches the raw gridpoints endpoint and parses `maxTemperature`/`minTemperature` arrays into Fahrenheit-keyed maps; private helper `parseDailyExtremes(node, isMax)` that handles unit conversion and date attribution for both directions.
2. **`NwsForecastMapper.kt`** — new pure `mergeGridpointTemperatures()` in companion object; new `async` block in `fetchFromNws`; merger called between `applyForecastPeriods` and `removePhantomFutureDays`; diagnostic `appLogDao.log("NWS_GRID_TEMP_MERGE", ...)` fires only when something changes.
3. **`NwsForecastMapperGridpointMergeTest.kt`** (new) — 8 pure-function tests: precedence, terminal-low upgrade (with phantom-filter survival check), brand-new 8th day, horizon cap default + custom, past-date rejection, partial fill (one null/one filled), today-inclusive boundary.
4. **`NwsApiTest.kt`** — 3 new fixture-based tests: C→F conversion (24.44°C → 75.99°F), overnight-low date attribution (`PT11H` window ending 07:00 PDT → date = May 7), max-of-multiple-intervals-on-same-date.

### Implementation Notes
- Defensive C→F handling — added `wmoUnit:degF` branch even though NWS only returns `degC` for gridded temperatures. Costs nothing; future schema change won't silently double-convert.
- Asymmetric default: unknown `unitCode` → treat as Celsius. NWS's documented behavior is the safer assumption.
- Test fixtures are timezone-agnostic — assertions compute `expectedDate` via `withZoneSameInstant(systemDefault).toLocalDate()` rather than hardcoding `"2026-05-07"`. Tests pass regardless of CI runner timezone.

### Verification
- `./gradlew :app:compileDebugKotlin` — clean.
- `./gradlew :app:testDebugUnitTest --tests "...NwsForecastMapperGridpointMergeTest" --tests "...NwsApiTest" --tests "...ForecastRepositoryPhantomDayTest"` — 25/25 tests pass (8 new merger + 3 new API + 7 existing phantom-day + 7 other API).

### Risks (recorded in plan)
- **R1 (highest):** `minTemperature` validTime semantics — the proposed `end.minusMinutes(1)` attribution is verified-by-convention but not yet against live data. Mitigation: diagnostic log `NWS_GRID_TEMP_MERGE` fires once per fetch with the dates filled, allowing post-merge cross-check against `/forecast`'s "X Night" periods.
- **R2:** Horizon cap (`horizonDays = 8`) prevents accidental ingestion if NWS extends gridpoints further.
- **R3:** Diagnostics noise — log only fires when something changed (not every fetch).

---

## 5. User Confirmation
**User Prompt:** *"Works. Thanks!"*

### Notes
Acknowledged ship; offered scheduled follow-up to evaluate diagnostic logs in 2 weeks and consider consolidating the now-three gridpoints fetches.

---

## 6. Follow-up Plan — Consolidate Three Gridpoints Fetches
**User Prompt:** *"make plan to consolidate the three gridpoints fetches into one"*

### Investigation
- `grep -rn "getSkyCover\|getQuantitativePrecipitation\|getDailyTemperaturesFromGridpoints"` — 5 call sites total:
  - 1 production: `NwsForecastMapper.fetchFromNws` (3 separate `async` blocks).
  - 4 test: `NwsApiTest.kt` (5 direct tests) + `NwsPrecipAmountIntegrationTest.kt` (3 MockK stubs).
- **Surprise discovery:** `NwsPrecipAmountIntegrationTest` uses **strict** MockK (`mockk()`, not `relaxed = true`) and stubs only `getSkyCover` + `getQuantitativePrecipitation`. After the 8-day-horizon change added a third call (`getDailyTemperaturesFromGridpoints`), the integration test should have started failing. Ran it manually — passed. Reason: the `try/catch` in `fetchFromNws` swallows MockK's "no answer found" exception and falls back to an empty bundle. Defensive try/catch boundary masking a test-fixture gap.

### Plan File
`/home/dcar/.claude/plans/i-notice-the-nws-idempotent-shannon.md` (overwritten — different task).

### Design Decisions
- **Replace, don't deprecate.** Every caller is in this repo and visible to grep. `@Deprecated` wrappers would be pure tax — a half-finished migration where the next person has to make the same removal decision again. CLAUDE.md is explicit ("don't add backwards-compatibility hacks"); the call surface is small enough that clean removal is one mechanical refactor.
- **Keep parsers private.** Forcing them into private helpers means the only way to get gridpoints data is through `getGridpointsBundle()` — preserves the architectural invariant of one entry point.
- **Preserve local variable names in `fetchFromNws`.** Destructuring the bundle into the same `skyCoverMap`, `gridQpfIntervals`, `gridDailyTemps` names that downstream code already reads → zero edits to the sky-cover overlay, QPF overlay, or merger call sites.

### Code Changes
1. **`NwsApi.kt`** — new `data class GridpointsBundle(skyCoverByHour, qpfIntervals, dailyTemperatures)`; new `getGridpointsBundle()` public method that fetches once and parses three sections; new private helpers `parseSkyCoverFromProperties()` and `parseQpfFromProperties()` (bodies lifted verbatim from old methods); existing `parseDailyExtremes()` reused unchanged. **Deleted:** `getSkyCover()`, `getQuantitativePrecipitation()`, `getDailyTemperaturesFromGridpoints()`.
2. **`NwsForecastMapper.kt`** — three `async` blocks collapsed to one `gridpointsDeferred`; three `.await()` calls collapsed to one with destructuring. All downstream code untouched.
3. **`NwsApiTest.kt`** — five tests' call sites swapped to `api.getGridpointsBundle(gridPoint).<field>`. Test fixture JSON unchanged. Assertions unchanged.
4. **`NwsPrecipAmountIntegrationTest.kt`** — three pairs of MockK stubs (`getSkyCover` + `getQuantitativePrecipitation`) collapsed to single `coEvery { nwsApi.getGridpointsBundle(gridPoint) } returns NwsApi.GridpointsBundle(...)`.

### Verification
- `./gradlew :app:compileDebugKotlin` — clean.
- `./gradlew :app:testDebugUnitTest` (4 test classes) — **25/25 passing.** Same count as before consolidation; the 5 previously-named tests still validate the same parsing behavior under their renamed labels via the new entry point.

### Effect Per Fetch Cycle
| Metric | Before | After |
|---|---|---|
| HTTP GETs to `/gridpoints/{office}/{x,y}` | 3 | 1 |
| JSON parses of ~250 KB payload | 3 | 1 |
| Response strings held simultaneously | 3 | 1 |

### Educational Notes
- **The cleanest collapse trick is keeping the local variable names.** Three async results destructured into the *same* names downstream code already used → one-block diff with no follow-on edits. Rename-while-you're-in-there is tempting but counterproductive in refactors of this shape.
- **`parseDailyExtremes` was already private and reusable.** The 8-day-horizon design (private helper parameterized by `isMax` flag) anticipated this consolidation without naming it. The new sibling helpers (`parseSkyCoverFromProperties`, `parseQpfFromProperties`) follow the same shape.
- **Test-name staleness is okay.** `getDailyTemperaturesFromGridpoints converts Celsius to Fahrenheit` is now a misleading name (the function under test is `getGridpointsBundle`). Deliberately *didn't* rename — test names still describe parsing behavior accurately, and rename churn complicates code review for no functional benefit.

---

## Final System State
- **NWS daily forecast horizon:** ~8 days (was ~7). The 8th day comes from raw `/gridpoints/` `maxTemperature`/`minTemperature`, renders with `condition="Unknown"` (UI handles gracefully).
- **HTTP fetches per `fetchFromNws` cycle:**
  - 1 × `/points/{lat},{lon}` (cached after first call)
  - 1 × `/gridpoints/{office}/{x,y}/forecast` (rendered, ~7-day periods)
  - 1 × `/gridpoints/{office}/{x,y}/forecast/hourly` (hourly periods)
  - **1 × `/gridpoints/{office}/{x,y}` (raw — was 3 separate fetches before consolidation)**
- **Diagnostics:** New `NWS_GRID_TEMP_MERGE` log line fires when the merger fills any date; new `getGridpointsBundle: skyCover=Nh qpf=N maxDays=N minDays=N` line fires once per fetch.
- **Code shape:** Three public methods removed; one public method (`getGridpointsBundle`) and three private helpers (`parseSkyCoverFromProperties`, `parseQpfFromProperties`, `parseDailyExtremes`) replace them. One pure merger function (`mergeGridpointTemperatures`) handles the integration with the existing `temperatureMap`.

## Files Touched
- `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt`
- `app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt`
- `app/src/test/java/com/weatherwidget/data/remote/NwsApiTest.kt`
- `app/src/test/java/com/weatherwidget/data/repository/NwsForecastMapperGridpointMergeTest.kt` (new)
- `app/src/test/java/com/weatherwidget/data/repository/NwsPrecipAmountIntegrationTest.kt`

## Plan Files Generated
- `/home/dcar/.claude/plans/i-notice-the-nws-idempotent-shannon.md` — written twice (once for 8-day horizon, then overwritten for consolidation).
