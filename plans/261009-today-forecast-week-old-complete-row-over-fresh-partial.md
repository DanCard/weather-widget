# Today's forecast drew a week-old 90/74 over the day's 69.5

## Report (Pixel 7 Pro, 2026-10-09, bug-report email 13:00)

"medio says high of 90 today, seems wrong." Refresh (Forecast History's refresh button) was
tapped; the 90 stayed.

## What the logs showed

- `TODAY_BAR_DEBUG widget=88 ... fHigh=90.0 fLow=74.0 ... sHigh=71.0 sLow=56.0` — `fHigh` is the
  **right** (today's forecast) bar; `sHigh` the left (yesterday's forecast) bar, which was right.
- The refresh did fetch: 12:58:53 `SNAPSHOT_SKIP_HINDCAST date=2026-10-09 source=OPEN_METEO
  prior_high=69.5`, then a targeted repaint of widget 88 — still `fHigh=90.0`.
- Widget site 37.40636,-122.02048 (moved there 12:36). Open-Meteo rows for Oct 9 at that site:
  - 37.406,-122.020 — 69.5 / **null low**, fetched 2026-10-09 12:36 (batch re-stamped 12:58:53)
  - 37.407,-122.020 — 90 / 74, fetched **2026-10-02** 11:34
- After 13:17 the widget moved to another site with a complete same-day row, and showed 69.

## Cause

Two rules each preferred "a complete row" with no bound on its age:

1. `ForecastDao.getLatestForecastsInRange*` (repaint path, via `ForecastSnapshotStore.getCachedData`)
   required `highTemp IS NOT NULL AND lowTemp IS NOT NULL` (added 2026-05-09 for NWS evening
   batches). Today's fresh row lacks the low (Open-Meteo/NWS drop it once passed), so the only row
   returned was Oct 2's. The tap path (`getForecastsInRange`, no filter) would have shown 69.5.
2. `PartialForecastDays.todayRow` (shared, Android + desktop): a partial batch row yields to "the
   newest stored row with both values" — again any age.

## Fix

- `PartialForecastDays.completeReplacement`: a complete row replaces today's partial one only if
  fetched within `COMPLETE_REPLACEMENT_MAX_AGE_MS` (24 h) of the newest stored row. Otherwise the
  fresh one-sided row stands and the missing side comes from hourly (`todayForecastRange`).
  Shared, so desktop (`DesktopWeatherDao.getDailyForecasts`) gets it too.
- `getLatestForecastsInRange` / `...ForSources`: newest batch with **at least one** temperature.
- Forecast History refresh button logs every tap (`HISTORY_REFRESH_TAP outcome=fetch_started |
  ignored_in_flight | ignored_no_source`) and `HISTORY_REFRESH_DONE ok= ms=`. The button stays
  enabled (dimmed) during a fetch so a second tap is logged instead of silently dropped — one of
  the user's two taps left no trace.

## Left bar (follow-up, same day — user: "fix the left bar too")

`PriorDayForecast.select` had no age bound either: the same Oct 2 fetch was frozen as Oct 9's
"yesterday's forecast" 90/74 at that site, and 95/74, 95/76 into Oct 7–8.

- `PriorDayForecast.MAX_PICK_AGE_HOURS` = 48: a fetch made more than 48 h before its anchor is no
  pick. 24–48 h still draws dashed (`STALE_SLACK_HOURS`, unchanged).
- Today's fallback (`fallbackToEarliest`) now takes the earliest row fetched **after** the anchor —
  what it was documented to do; before, "earliest usable row" was the week-old one when it existed.
- `planPriorForecasts` clears a frozen side whose value matches a still-retained too-old fetch
  (written before the bound). Otherwise still monotone (pruned history keeps its values).
- The freeze runs over the current location's rows, so the Borregas Ave rows (37.406,-122.020)
  clear on the first sync back there.

## Not changed

`ForecastOverlaySettle` (past days' right bar: last forecast fetched before each extreme) has no
age bound either; the Oct 7–8 settled overlays at that site are 95 from the same Oct 2 fetch.
