# Session Log — Tomorrow.io: forced verification, build-type defaults, quota throttle

**Date:** 2026-05-29
**Branch:** main
**Follow-up to:** `session-logs/260529-tomorrow-io-historical-rainfall.md` (the precip-field +
provenance fix) and its commit `c5b4f2b`
**Area:** Tomorrow.io rain actuals — on-device verification, source-default policy by build type,
API-call/quota reduction, and temp-code cleanup
**Commits produced this session:** `c5b4f2b` (precip fix + defaults), `068f3ed` (current-temp throttle)

---

## Prompts (verbatim, in order)

1. *"We have been working on actual rainfall in daily forecast history. You can review recent past
   session-logs/ . All the APIs except tomorrow show rainfall for yesterday. You can use the emulator
   to retrieve logs. It does seem like history for tomorrow api is working."*
2. *(AskUserQuestion response)* *"Does tomorrow.io provide historical rainfall info? If yes I want
   that displayed."*
3. *"Yes force a refresh for tomorrow, consider custom temporary code to do that."*
4. *"Because of the tomorrow.io quota issue, is it possible for release build not to enable tomorrow.io,
   but yes for debug builds? Disabling can be done via configurable app settings."*
5. *"Also want tomorrow.io low on the preference set. Below silurian"*
6. *"Wondering about a brainstorm list to further limit tomorrow.io call to avoid rate limit quota.
   How often do we check for current temp? Maybe reduce that to once an hour?"*
7. *"Do this for which ever api is 4th on the list or lower. Do both"*
8. *"Check if tomorrow.io backfill for actual rainfall amount works"*
9. *"emulator-5554 does not show rainfall in daily forecast view, history. No rainfall shown for
   yesterday, or yesterday night."*
10. *"Does tomorrow.io api have rain actuals in history? If yes, why doesn't it display?"*
11. *(interrupt)* *"Why not query the widget db and logs instead?"*
12. *"clean up temporary code"*
13. *"Write a new session log, updated with info since last written session log. Include all prompts."*

---

## 1. Forced on-device verification of the precip fix (prompt 3)

The precip fix (`precipitationAccumulation` field + `providesHistoricalActuals = true`) couldn't be
verified live because data was fresh (~15 min) and `ACTION_REFRESH` only fetches when a source is stale.

- Added a **temporary** `force_refresh` broadcast extra to `handleRefreshAction` in
  `WeatherWidgetProvider.kt` (marked `THROWAWAY_2026-05-29`) to bypass the staleness gate.
- First forced fetch → **HTTP 429** (Tomorrow.io free plan ~25 req/hr, exhausted by my API probes +
  repeated forced fetches). Stopped hammering; ran one delayed retry via a background job after the
  quota window cleared.
- **Retry succeeded.** Logs + DB confirmed the pipeline works end-to-end:
  - `CURR_FETCH_SOURCE_RESULT source=TOMORROW_IO success=true`
  - `DAILY_EXTREME_OVERWRITE date=2026-05-28 src=TOMORROW_IO precip=null->0.0`
  - **Today (5/29): `TOMORROW_IO_MAIN` captured 0.762 mm at 04:00** (real `precipitationAccumulation`),
    which aggregated into `daily_extremes` 5/29 total=0.762 / night=0.762 (04:00 → night bucket).
    The observation sum and the daily total matched exactly.

**Conclusion:** the fix is mechanically sound. Before it, Tomorrow.io logged 0.0/null (intensity field);
after it, real rain is captured the moment it appears in-window.

## 2. Build-type source defaults + Tomorrow.io reorder (prompts 4, 5)

`WidgetStateManager.DEFAULT_VISIBLE_SOURCES` was the single seed for `getVisibleSourcesOrder()`.

- Changed from `const val` → `val` (BuildConfig.DEBUG isn't a compile-time constant) and made it
  build-conditional:
  - **Debug:** `"NWS,OPEN_METEO,SILURIAN,TOMORROW_IO"` — Tomorrow.io enabled but **last** (below
    Silurian, per prompt 5; also means it's never the primary via `getPrimarySource().first()`).
  - **Release:** `"NWS,OPEN_METEO,SILURIAN"` — Tomorrow.io off by default to spare its ~25/hr quota.
- Tomorrow.io stays listed in Settings (`SettingsActivity.allSources`), so release users can enable it
  manually (it shows unchecked/dimmed). Verified `:app:compileReleaseKotlin`.
- Updated `WidgetStateManagerTest` default-order assertion + comment (tests run debug ⇒ Tomorrow.io
  present last).

## 3. Quota-reduction brainstorm + per-source throttle (prompts 6, 7)

**Call-budget analysis (the why):** `fetchTomorrowIoCurrent` calls `getForecast()` = **2 API calls**
(hourly+daily) per cycle; current-temp fetches **all enabled sources**; charging loop runs every
**10 min** (screen on) / 16 min (off); the 5-min freshness gate is a single global timer. ⇒ ~12
Tomorrow.io calls/hr from current-temp alone → 429s (98 calls logged 5/28).

**Implemented** (`068f3ed`): a position-based throttle — **any source ranked 4th or lower** in the
visible order has its current-temp network fetch limited to **once/hour** (`LOW_PRIORITY_RANK_THRESHOLD = 3`,
`LOW_PRIORITY_CURRENT_TEMP_INTERVAL_MS = 60 min`). Top 3 keep the ~10-min cadence.
- New helpers `WidgetStateManager.shouldFetchCurrentTempForSource` / `markCurrentTempFetched`
  (SharedPreferences-backed, mirrors the missing-data cooldown pattern).
- `CurrentTempRepository.refreshCurrentTemperature` computes rank from `getVisibleSourcesOrder()`,
  filters throttled low-priority sources (logs `CURR_FETCH_THROTTLE_SKIP`).
- **Bypass** on forced refresh or explicit single-source request (displayed source stays fresh).
- **Mark-on-attempt:** timestamp written before the fetch, so a 429 also consumes the window — a
  built-in backoff (the global rate-limiter otherwise *resets* on failure and would re-hammer).
- Effect: Tomorrow.io ~12 calls/hr → ~4/hr, under quota. Makes the "single-call instead of getForecast"
  idea moot for quota. Brainstorm saved to `notes/260529-tomorrow-io-quota-call-reduction.md`.

## 4. "No rain for yesterday" diagnosis (prompts 8–11)

Queried the **widget DB + render logs** (not the live API — per prompt 11):
- `daily_extremes` for TOMORROW_IO: 5/27 = 0.0, **5/28 (yesterday) = 0.0**, 5/29 (today) = 0.762.
- Backfilled `TOMORROW_IO_MAIN` obs for 5/28: 24 rows, 12 with precip, **all summing to 0.0**.
- `DAILY_RENDER` log: the widget is displaying **`source=TOMORROW_IO`**.

**Answer:** Not a rendering bug. Tomorrow.io *has* hourly records for yesterday but every value is 0.0
— its nowcast recorded no rain in the hours it could reach (evening), and the rest of yesterday was
beyond its 24h fetch wall by the time we fetched. The widget is on Tomorrow.io, so it correctly shows
no rain. The other three sources have yesterday's rain in `daily_extremes` (NWS 7.3, Open-Meteo 5.1,
Silurian 3.55) — switching the source displays it. (Dedicated `/v4/historical` endpoint is 403 Access
Denied on our plan; only the rolling <24h timelines window is available.)

## 5. Temp-code cleanup (prompt 12)

- The `force_refresh` hook had been committed inadvertently in `c5b4f2b` (pushed), then its removal
  was committed in `068f3ed`. `HEAD` and the working tree are clean (0 hook references in `app/src`).
- Removed `/tmp` throwaway artifacts (retry script, DB snapshots, probe JSON, screenshots).
- **Residual:** the hook still exists in the historical pushed commit `c5b4f2b`. Deliberately NOT
  rewritten — it's published, and the hook is benign (only fires on a broadcast extra nothing sends).
  Full excision would need `rebase -i c5b4f2b^` + force-push; not done unprompted on `main`.

---

## Key lessons

- **"Does source X have history?" is a question for the widget DB, not the live API** (prompt 11). The
  DB is the authoritative record of what the widget actually holds and renders; live probes only answer
  what the API *could* return. 24 backfilled rows all reading 0.0 is the tell that data isn't *missing*
  — the source genuinely recorded no rain.
- **A fetch-window cap can double as a provenance guarantee and a quota knob.** Tomorrow.io's 23h
  startTime cap makes its past hours genuine recent actuals (safe to persist) *and* bounds how far back
  it can ever fill — the same constraint behind both the provenance flag and the "can't show yesterday."
- **Position-based policy > per-API hardcoding.** Throttling "rank ≥ 4" and defaulting Tomorrow.io to
  the bottom means any future quota-limited source ranked low is handled automatically.
