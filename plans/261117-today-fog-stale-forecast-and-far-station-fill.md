# NWS Today drew dense fog on a clear morning: a stale forecast fog top, plus a far station filling a feed gap

User (2026-11-17, emulator): "Why fog indicator? NWS today column."

## Evidence (emulator-5554, backup `backups/20261117_101124_sdk_gphone64_x86_64_emulator-5554`)

1. Screenshot 10:11: the NWS Today column shows a bright ellipse from the low (42.6°) to about 54°.
2. logcat 10:10: `DAILY_FOG_DRAWN date=2026-11-17 level=4 top=53.5 vis=0.125`. This is the render-time
   `combine(observed, forecast)`: DENSE and ⅛ mi come from the observed side, and top 53.5 comes from
   the forecast side, because the higher top wins.
3. **Forecast side.** The NWS `forecasts` row for 11-17, fetched at 10:09:28, has
   `fogLevel=2 top=53.5 vis=3.0`, but that fetch's hourly rows have no fog in any hour (01:00–12:00 are
   "Mostly Clear"/"Clear"/"Sunny" with 6–10 mi visibility). The 53.5 comes from the **01:10** fetch,
   which had "Patchy Fog" at 06:00, 07:00 and 09:00 (52°), with 10:00 at 55°: (52 + 55)/2 = 53.5.
4. **Observed side.** The NWS actuals provider's rows (`api='NWS'`) were picked hour by hour by
   `ObservedMorningFog.nearestPerHour`:

   | hour | station | report |
   |---|---|---|
   | 04–05 | KNUQ 3.8 km | `10SM CLR 06/06` |
   | 06–07 | **KSJC 15.9 km** | `1/4SM FG` … `M1/4SM FG VV001`, until 07:50 |
   | 08–09 | KNUQ | `10SM CLR` |

   KNUQ **did** report through the gap, `10SM CLR` every 20 min (06:15, 06:35, 06:55, 07:15, 07:35,
   07:55), but only in the `METAR` and `SYNOPTIC` rows. The NWS API feed dropped those reports (a known
   gap: NWS API METARs lag or go missing). With KNUQ silent in the NWS feed, KSJC, four times farther
   away, stood in for 06:00–07:59.

## Root causes

### 1. The forecast fog cannot clear because the persist path's stored hours start at 08:00

`ForecastPersistRun.persistDaily` builds each day's summary as
`carryForward(carryForward(fromPayload, provider), fromStored)`, and `DailyForecastWrite` then falls
back to the prior row (`fog = incoming.fog ?: prior.fog`).

- **fromPayload is null.** The 10:09 download's hours start at 01:00, so
  `DailyHourlySummaries.windowCovered(00:00–12:00)` fails and `ForecastMorningFog.forDate` returns
  null ("can't tell").
- **fromStored is null too.** The stored read is
  `host.storedHourly(DailyPrecipPeriods.readStartMs(firstDate), …)`, and `readStartMs` is
  **08:00**, the rain day window. That read can never cover the 00:00–12:00 fog window, so fog from
  stored hours is null for the first date of every fetch, which is today.
- So `prior.fog` (the 01:10 fetch's LIGHT/53.5) carries forward unchanged. Once a fetch's download
  starts after midnight, today's forecast fog is frozen at the last fetch whose download did start at
  00:00, even after the provider drops the fog.

### 2. Any station, however far, stands in for an hour the nearest one missed

`nearestPerHour` takes, for each hour, the nearest station **that reported in that hour**, with no
distance limit. When the nearest station drops out of the provider feed, any station farther away
fills the hour, and a morning that was clear at your location reads as dense fog.

## Proposed fix (`:shared`, so both platforms change together)

### Fix 1: read stored hours from the fog window's start

In `ForecastPersistRun.persistDaily`, start the stored read at the earlier of the rain read start and
the fog window start:
`minOf(DailyPrecipPeriods.readStartMs(first), MorningFogWindow.startMs(first, zone))`.

- The DAO returns the newest fetch per hour, so 00:00 comes from the 01:10 fetch ("Mostly Clear") and
  01:00–12:00 from the 10:09 fetch. The window is covered and fog becomes `MorningFog.NONE`, a real
  answer that replaces the stale fog.
- The rain/cloud summaries filter their own windows, so the extra 00:00–08:00 rows do not change them.
- No schema change. The stale stored row corrects itself on the next fetch.

### Fix 2: a fill-in station must be near the morning's nearest station

In `ObservedMorningFog.nearestPerHour`, the baseline is the nearest fog-capable station in the day's
readings. An hour that baseline station missed is filled only by a station within
`FILL_IN_MAX_DISTANCE_RATIO` (proposed **2×**) of the baseline distance. Otherwise the hour has no
answer and is skipped, not counted as fog.

- 2026-11-17: the baseline is KNUQ at 3.8 km, so fill-ins must be within 7.6 km. KSJC (15.9 km)
  is rejected. KPAO (5.0–6.1 km) would still be allowed.
- Hours 06–07 are skipped, and KNUQ's other hours read `10SM CLR`, so observed fog is **NONE**.
  `DailyMorningFog.forColumn` then lets forecast fog through only from the current hour to noon,
  which is also NONE once fix 1 lands. **Expected result: no ellipse on 11-17.**
- Past days are recomputed by `DailyBlendRecomputeRun` through the same `forDate`, so stored
  `observedFog*` gets the same rule.

**Open question (your call):** KNUQ's clear reports exist in the `METAR` and `SYNOPTIC` rows. Another
option is for the fog reader to also use raw METAR text from other feeds for **the same stations** the
provider already reports. That would bring back the real data for the gap instead of skipping the
hours. It crosses the "one actuals provider" boundary, so it's left out of this plan unless you want it.

## Tests

Each test below fails on the current code.

1. `ObservedMorningFogTest`: the 11-17 sequence (KNUQ 3.8 km clear at 04, 05, 08, 09; KSJC 15.9 km
   `1/4SM FG` at 06–07) gives `MorningFog.NONE`. Today's code gives DENSE. Also: a fill-in at 1.6×
   (KPAO-like) still counts.
2. `ForecastPersistRunTest`: stored hours include an old fetch with "Patchy Fog" at 06–09 and a newer
   fetch with all-clear hours 01–12. The new download starts at 01:00 and the prior row holds
   LIGHT/53.5. The written summary's fog must be NONE. Today's code keeps 53.5.
3. **Integration with real SQLite** (`DesktopForecastPersistHostContractTest`, and the Room
   counterpart `RoomForecastPersistHostContractTest`): run case 2 through the real DAO's
   newest-per-hour read to confirm the 00:00 row from the older fetch is returned and coverage holds.
4. `DailyColumnFogTest`: observed NONE (from the rule in case 1) plus forecast row NONE gives no fog for
   today.
5. `./scripts/unit-tests.sh`, then the end-of-task gate
   `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`, then a screenshot of
   the emulator NWS Today column and a `DAILY_FOG_DRAWN` logcat check (no line for 2026-11-17).

## Docs

- `arch/daily-history-extremes.md` "Observed morning fog": add the fill-in distance rule.
- `arch/ARCHITECTURE.md` daily fog section: note that the forecast fog summary reads stored hours from
  the fog window's start. Link here rather than repeating the 2× constant.
