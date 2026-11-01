# Desktop source switch: the "aw020 66°" flash

## Problem

Switching API sources on the desktop hourly temperature graph briefly showed `aw020 66° @ 2:40 pm`
(or `f4751 66° @ 2:45 pm`), then `knuq 64.4° @ 2:35 pm`. The readings were real. The frame was not:
for about 1.2 s the graph mixed the new source's ID with the old source's data.

Log from NWS → Silurian on 2026-11-01:

| Time | Event | Dominant-station label |
|---|---|---|
| 14:58:03.95 | Config changes to `SILURIAN`, but the snapshot still holds NWS rows | |
| 14:58:04.01 | Graph redraws with no forecast (`NAN_TEMP_INDICES`) and 6 labels | `aw020 66° @ 2:40 pm` |
| 14:58:05.18 | New snapshot arrives (`data_ready sinceSwitchMs=1233`) | |
| 14:58:05.28 | Normal graph with 9 labels | `knuq 64.4° @ 2:35 pm` |

## Root cause

1. **Mismatched inputs:** `WidgetPopup` took `hourly` and `observations` from the snapshot, but
   `displaySourceId` from the config. The config changes on the click; the snapshot reloaded 325–1356 ms
   later (median 1167 ms over 17 switches).
2. **The forecast disappears:** the old snapshot has no hourly rows for the new source, so the forecast
   lookup comes back empty. Observations still match, because the snapshot holds every API's rows.
3. **KNUQ drops out:** its last reading was 2:35, so reaching the 2:40 blend point needs the forecast, and
   `extrapolateForward` returns null without one.
4. **AW020 wins by default:** it is the only station with a reading at 2:40, so it is the one named.

The same mismatch dropped the forecast line and some labels for that moment, and it caused a false
`HOURLY_PAN_CHECK action=Fetch` (`rowsBySource={NWS=874}` beside `displaySource=SILURIAN`) that flashed
the "Fetching…" banner for 54 ms. The header had this bug too, fixed in
[261031](../plans/261031-desktop-header-source-label-first.md).

## Changes

1. **`DesktopWidgetPopup`:** the temperature, precipitation and cloud graphs and the nowcast use
   `snapshot.sourceId`.
2. **`DesktopDailyForecastModel`:** the daily columns and today overlay use `forecast.sourceId`.
3. **Pan check:** waits until the snapshot matches the selected source, keyed on `snapshot.sourceId` so
   it runs once the rows arrive.
4. **Day taps:** routing uses the snapshot's source. During a switch the on-demand fetch decision is left
   to the pan check.
5. **`TemperatureGraph`:** adds an `HourlyDominantStationId` semantics property, because Canvas text is
   not visible to tests.
6. **`arch/operations.md`:** documents the behavior and the `NAN_TEMP_INDICES` tell.

### Accepted trade-off

The source button changes on the click, and the old source's complete graph stays until the new data
arrives. Speeding up the reload would shorten the wait, but is separate work.

## Tests

`DesktopSourceSwitchGraphConsistencyTest` copies the real station pool. All three tests failed on the old
code, including `expected:<KNUQ> but was:<AW020>` and a false `action=Fetch` against `rowsBySource={NWS=85}`.

`DesktopNoHourlyDayClickTest` and `DesktopDailyParityTest` hard-coded `sourceId = "NWS"` while using other
sources, which production cannot do outside a switch. They now state their real source. The parity
expected output is unchanged.

## Verification

- `:desktop:test`: 829 tests, 0 failures, 1 skipped.
- Live, in the rebuilt app on the temperature view: 4 switches (`data_ready` 492–621 ms), every label
  `knuq`, 0 `NAN_TEMP_INDICES`, no `HOURLY_PAN_CHECK` or `HOURLY_FETCH_BANNER` rows.
- The precipitation and cloud views were not switched live.
- Android was not checked for the same gap.

## Open: a new source with no saved data

Not built, and not reproduced: this comes from reading the code. `loadCached` returns null when a source
has no rows, and the UI then keeps the old snapshot. If the fetch also fails, one source's forecast could
stay under another's name, with nothing showing it is wrong.

Proposal, awaiting approval:

| Case | What to do | When |
|---|---|---|
| Load is slow | Banner, "Loading Silurian…" | About 3 s (slowest switch measured: 1.36 s) |
| No saved data, and the fetch failed or returned nothing | Blank the graph, "No Silurian data for this location" | Right away |
| Load is stuck with no answer | Same blank, as a backstop | About 15 s (a real 5.6 s SQLite lock wait still succeeded) |

- **Keep the header visible:** `forecast = null` removes the source button, leaving no way to switch away.
  This needs an empty snapshot for the new source instead.
- **Cover every view:** the daily view has the same problem.
- **Normal switches stay silent:** source changes must not show notices, and the banner only appears when
  something is wrong.

## Related

`scripts/fast-desktop-restart.sh` relaunches the existing build, and its header points to
`build-exe-and-restart.sh`, which does not exist. Use `scripts/buildStart-desktop.sh` to rebuild. Not
changed here.
