# Reducing Tomorrow.io (low-priority API) call volume to avoid rate-limit quota

**Date:** 2026-05-29
**Context:** Tomorrow.io's free plan caps at ~**25 req/hr, 500/day, 3/sec**. Forcing fetches during
the rain-actuals verification produced repeated `HTTP 429` (`code 429001`). This note captures the
call-budget analysis and the mitigation we implemented.

## Call-budget analysis (why it 429s)

- **`CurrentTempRepository.fetchTomorrowIoCurrent` calls `TomorrowIoApi.getForecast()`**, which makes
  **2 API calls** (hourly timeline + daily timeline) — even though current-temp only needs the temp.
- **Current-temp fetches every enabled source** (`getVisibleSourcesOrder()`), so Tomorrow.io is hit on
  every cycle it's enabled.
- **Charging loop cadence:** every **10 min** (screen on) / **16 min** (screen off) while charging
  (`CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES`). The 5-min freshness gate
  (`CURRENT_TEMP_FRESHNESS_MS`) is a single *global* `lastFetchTime`, so a 10-min loop always clears it.
- Plus the **full data fetch** (60–480 min, battery-aware) and **screen-unlock** fetches — each +2 calls.

⇒ Charging + screen on ≈ 6 cycles/hr × 2 = **~12 Tomorrow.io calls/hr from current-temp alone**, before
full fetches and unlocks. Logged usage hit 98 calls on 2026-05-28. That's the quota breach.

## Implemented (2026-05-29): per-source current-temp throttle for rank ≥ 4

Position-based, not API-specific: **any source ranked 4th or lower** in the visible order has its
current-temp network fetch limited to **once per hour** (top 3 keep the ~10-min cadence).

- `WidgetStateManager.shouldFetchCurrentTempForSource(sourceId, minIntervalMs)` /
  `markCurrentTempFetched(sourceId)` — SharedPreferences-backed per-source timestamp (mirrors the
  missing-data cooldown pattern). Key prefix `current_temp_fetch_`.
- `CurrentTempRepository.refreshCurrentTemperature` — computes each candidate's rank from
  `getVisibleSourcesOrder()`; filters out rank ≥ `LOW_PRIORITY_RANK_THRESHOLD` (3) sources whose last
  fetch was < `LOW_PRIORITY_CURRENT_TEMP_INTERVAL_MS` (60 min) ago. Logs skips as `CURR_FETCH_THROTTLE_SKIP`.
- **Bypass:** a forced refresh or an explicit single-source request (user toggled to that source) ignores
  the throttle, so the displayed source stays fresh.
- **Mark-on-attempt:** the timestamp is written *before* the fetch, so a 429/failure also consumes the
  window — a built-in backoff that stops rapid retries (note: the global rate-limiter *resets* on failure,
  so without this it would re-hammer).

**Effect:** Tomorrow.io drops from ~12 calls/hr to ~2/hr (1 current fetch/hr × 2 calls) + the full fetch
(~2/hr at the 60-min charging interval) ≈ **~4/hr**, comfortably under the 25/hr cap. Future quota-limited
APIs ranked low are throttled automatically.

## Remaining options (not implemented — for reference)

| # | Idea | Status |
|---|------|--------|
| 1 | Single lightweight current call instead of `getForecast`'s 2 (hourly-only). Precedent: Silurian fix in `silurian_current_temp_actual_line`. | **Moot for quota** after the throttle (~4/hr already). Still a tidy efficiency win if revisited. |
| 3 | Interpolate Tomorrow.io current temp from cached hourly (`TemperatureInterpolator`), zero extra calls. | Open — would remove current-temp calls entirely for low-priority sources. |
| 4 | Current-temp fetches only the *displayed* source, not all enabled. | Open — broader behavior change; trades some cross-source freshness. |
| 5 | Explicit 429 cooldown ("skip for the rest of the hour"). | Partially covered by mark-on-attempt; a 429-specific longer backoff could be added. |
| 6 | Daily/hourly budget guard using the existing `api_usage_stats` table. | Open — graceful degradation near 25/hr / 500/day. |
| 7 | Globally raise `CHARGING_INTERVAL_MINUTES` to ~60. | Rejected — blunt; degrades current-temp responsiveness for all sources. |

## Files touched

- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
- `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`
