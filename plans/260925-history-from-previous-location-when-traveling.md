# Show history from a previous location when traveling, marked as such (dashed bars)

## Request (bug report email 2026-09-24, Pixel 7 Pro, v26091701)
> No history when on the move. I suggest showing history for previous location where history
> exists. … just want to know the delta from yesterday … the location isn't that important.

Follow-up 2026-09-25: this is the **same source** at a different place, not a cross-source fallback.
"Would be nice if could somehow designate when history is stale or not the same location."

## Evidence (Pixel DB backup 2026-09-25 06:22, now in Lviv 49.83, 24.03)
| date | at Lviv (current site) | at Kyiv 50.45, 30.49 (~470 km) |
|---|---|---|
| 09-19..09-23 | `forecast_only_row`: forecast high/low only, no measured actual | SILURIAN measured actuals (`blend_recompute`), e.g. 09-23 61.9/44.9 |
| 09-24 | SILURIAN measured 59.5/47.8 (first day here) | — |

The daily view shows Wed/Thu at Lviv as forecast-only bars even though measured Silurian history
exists for those days a few hundred km back along the trip. (Open-Meteo had no measured rows anywhere
because of the provider bug fixed in `b7f2839d`; those fill in on recompute.)

## Why it happens
Every history read is location-scoped through the shared `LocationMatch` box (±0.1°):
`DailyHistoryDao.getExtremesInRange` (`ROOM_WHERE`) → `ObservationResolver.extremesToDailyActualsBySource`
(filters again with `TOLERANCE_DEG`) on Android, and the equivalent read in
`DesktopWeatherRepository` on desktop. A row filed at a previous site can never match after a move.

## Proposed change
1. **Shared selection rule** (new pure function in `:shared`, used by both platforms): for each
   past date and source, when the current site has **no measured** row (`computedHigh/Low` null),
   take the measured row for the same source and date from another site. Prefer the site nearest the
   current location; break ties by the latest `updatedAt`. Never replace a measured row at the current
   site. Forecast-only rows at the current site keep supplying the forecast overlay (yellow bar).
2. **Carry provenance**: the chosen `DailyHistory` is tagged `borrowedFrom = (lat, lon, distanceKm)`,
   so the renderer knows it's from somewhere else.
3. **Mark it on the widget and desktop** in the daily view's past columns, in the style picked below.
4. **Read paths only.** Nothing is written or re-filed under the new site. Accuracy statistics,
   Forecast History and the data written each day stay strictly location-scoped. Only the display is
   affected.
5. **Out of scope for now**: the hourly-graph "+x from yesterday" label and the today-column
   `-5.0 fcst` delta. Both blend raw observations near the current site and would need their own
   design.

## Tests
- Shared: selection prefers the current site's measured row; falls back to the nearest other site;
  ignores forecast-only rows as donors; honours a distance cap if one is chosen.
- Android Robolectric + desktop model test: a borrowed past day renders with the marker, and a
  native day doesn't.
- Integration test (DAO + store) using the Kyiv → Lviv rows above.

## Verification
Pixel (currently Lviv): Wed/Thu show the Kyiv-measured bars with the marker. Emulator-5554 and the
desktop app: same.

## Decisions (user, 2026-09-25)
- **Marker (final)**: a **dashed** bar, with no pin and no dimming. Dimming reads as the cloud cue
  (grey = cloudy), so it was rejected; the pin was dropped for layout reasons (mockup options A–D).
  Labels are unchanged.
- **Distance**: no limit. The nearest site with measured history always wins.

## Part 2: dim an old "yesterday's forecast" bar in the Today column
Same idea: a dimmed bar is a stand-in. `DailySnapshotSelector.selectPriorDaySnapshot` still picks the
same row (the user chose to keep showing old snapshots; see memory
`snapshot_selector_no_max_age_accepted`), but callers learn its age. If it was fetched more than
**48h** before now (a real "yesterday's forecast" is 24–48h old), the Today column's left (snapshot)
bar is drawn **dashed**, like borrowed history. **No glyph or age label** (user, 2026-09-25): the
Today column is already crowded.
- Shared: `isStaleSnapshot(fetchedAt, now)` (or a result type that carries the age) next to the selector.
- Android `DailyBarRenderer.drawTodayTripleBar` and desktop `DailyForecastGraph` draw it dashed.
- Tests: shared boundary (47h not dimmed, 49h dimmed); Android + desktop render check.
- Verify: emulator-5554 (09-18 snapshot) and 5556 (09-16) show a dashed left bar; the Pixel's doesn't.
- **Only yesterday** (user, 2026-09-25, mid-implementation): at most one day of previous-site
  history is shown. Only `today - 1` is borrowed; older past days stay local-only.

## Result (2026-09-25)
- Shared `PreviousSiteHistory` (yesterday only, nearest measured donor, never replaces local measured)
  and `StandInBarStyle` (dash intervals as stroke-width multiples); `DailySnapshotSelector.isStale` (>48h).
- Android: `DailyHistoryDao.getMeasuredExtremesForDateAnySite` → `DailyActualsStore`
  (`PREVIOUS_SITE_HISTORY` debug log); `DayData.actualsFromOtherSite` / `snapshotIsStale`;
  `DailyBarRenderer` dashes via `DashPathEffect`. A day borrowed wholesale still triggers the
  `actuals_history` refresh (`borrowedWithoutLocalRow`).
- Desktop: `DesktopWeatherDao.getMeasuredExtremesForDateAnySite` → `DesktopWeatherRepository.loadDailyActuals`;
  `DesktopDailyDay` flags; `DailyForecastGraph` dashes via `PathEffect.dashPathEffect`.
- Tests: `PreviousSiteHistoryTest`, `DailySnapshotSelectorTest.staleOnlyPast48Hours`,
  `DailyActualsStorePreviousSiteTest` (Room integration), `DailyViewHandlerTest` (x2),
  `DailyForecastGraphRendererRoboTest.standInBars_areDashed_onlyWhenFlagged`,
  `DesktopDailyForecastModelTest` stand-in flags. Full shared/app-unit/desktop suites pass.
- Devices: all three have a local measured yesterday, so nothing is borrowed right now (no
  `PREVIOUS_SITE_HISTORY` line). The Today snapshot bar is dashed on emulator-5554 (09-18),
  emulator-5556 (09-16) and the Pixel (09-18 from the earlier Lviv stay; the 09-24 forecasts were
  fetched en route, outside the Lviv box).
