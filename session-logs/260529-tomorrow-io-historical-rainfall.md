# Session Log — Tomorrow.io historical rainfall (yesterday showed no rain)

**Date:** 2026-05-29
**Branch:** main
**Follow-up to:** the 2026-05-28 historical-rainfall provenance audit (which set Tomorrow.io `providesHistoricalActuals = false`)
**Area:** Tomorrow.io rain actuals in the daily forecast history

---

## Problem reported

In the daily view, every API except **Tomorrow.io** showed rainfall for yesterday (2026-05-28). User asked: *does Tomorrow.io provide historical rainfall info? If yes, display it.*

## Live-data confirmation (emulator-5554 DB)

`daily_extremes` for yesterday (epoch-millis `1779926400000` = 2026-05-28):

| Source | total | day | night |
|--------|-------|-----|-------|
| NWS | 7.3 | 0.8 | 6.5 |
| Open-Meteo | 5.1 | 2.6 | 2.5 |
| Silurian | 3.55 | 1.95 | 1.60 |
| **Tomorrow.io** | **null** | **null** | **null** |

`TOMORROW_IO_MAIN` observations: 24 rows for 5/28, **0 non-null precip**; even the older 5/27 rows (captured before the audit nulled them) were all **0.0**.

## Root cause — two compounding issues

1. **Wrong field.** `TomorrowIoApi` stored `precipitationIntensity` (an instantaneous mm/hr rate sampled at the hour boundary) as the per-hour *amount*. It reads ~0 for hours where rain fell between samples → Tomorrow.io logged 0.0 even on rainy hours.
2. **Over-broad suppression.** The 2026-05-28 audit set `providesHistoricalActuals = false`, so `saveHistoricalActuals` nulled all its past precip.

## Live API probes (current key)

- `/v4/historical` (dedicated history, POST) → **403001 Access Denied** — no multi-day history product on our plan.
- `/v4/timelines` startTime 48h ago → **403003** — blocked beyond 24h.
- `/v4/timelines` within 24h → **200**, and returns real `precipitationAccumulation` / `rainAccumulation` (sample hour: intensity `0`, accumulation `0.01`).

**Answer:** Tomorrow.io has no multi-day archive, but a genuine **rolling <24h actuals window**. Since `TomorrowIoApi` hard-caps `startTime` at `minusHours(23)`, every past hour it returns is <24h old — recent nowcast/analysis, not stale forecast. So the VC/OWM forecast-as-actual rationale does NOT apply to it.

## Changes (uncommitted)

1. **`TomorrowIoApi.kt`** — read `precipitationAccumulation` (×25.4 → mm) instead of `precipitationIntensity` for `precipAmountMm`, hourly + daily.
2. **`WeatherSource.kt`** — `TOMORROW_IO.providesHistoricalActuals = true`, with a doc note that this is safe *only* because of the 23h fetch cap (do not widen it). Updated the `saveHistoricalActuals` comment in `ForecastRepository.kt` to match.
3. **`WeatherSourceHistoricalActualsTest.kt`** — moved TOMORROW_IO to the expected-true set; clarified why it's not forecast-only.
4. **`TomorrowIoApiTest.kt`** — fixtures use `precipitationAccumulation`; added assertions on `precipAmountMm` (0.01in→0.254mm hourly, 0.2in→5.08mm daily) so a revert to intensity fails.

**Verification:** `:app:testDebugUnitTest` for `TomorrowIoApiTest`, `WeatherSourceHistoricalActualsTest`, and `data.repository.*` — all PASS. APK installed on all devices.

## Pending end-to-end device confirmation

Data was only ~15 min old, so `ACTION_REFRESH` went UI-only (no fetch). A real fetch + `daily_extremes` recompute fires on the next stale background cycle (NWS staleness ~60 min) or periodic fetch — the temp `force_refresh` hack from the prior session stays reverted. After that, `TOMORROW_IO_MAIN` past hours should carry real precip and yesterday's totals should populate (building up over continuous operation as each hour is captured while <24h fresh).

## Key lesson

"Does source X provide history?" is an empirical question about the **key/plan**, not the vendor — `/v4/historical` is denied here even though Tomorrow.io documents it. And a fetch window cap can itself be a provenance guarantee: a 23h `startTime` ceiling means past hours are inherently recent, which is what makes flipping the flag honest. (Memories updated: `historical_actuals_provenance.md`, `tomorrow_io_24h_history_limit.md`.)
