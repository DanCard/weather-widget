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

## Follow-up: a new source with no saved data

`loadCached` returns null when a source has no rows, and the UI used to keep the old snapshot. If the fetch
also failed, one source's forecast stayed under another's name, with nothing showing it was wrong.

The first proposal was a 3 s banner and a 15 s blank. Measuring 844 switches (median 472 ms, 95th percentile
700 ms, slowest 2871 ms, none over 3 s) showed 15 s was too cautious, so there is one 3 s threshold:

| Case | What shows | When |
|---|---|---|
| Normal switch | Old graph, no message | Under 3 s |
| Load is slow | Blank, "Loading Silurian…" | At 3 s |
| No saved rows, fetch running | Blank, "Loading Silurian…" | Right away |
| No saved rows, fetch ended | Blank, "No Silurian data for this location" | Right away |

- **Knowing the fetch ended:** with no cache the daemon always fetches and only notifies at the end, so a
  daemon notification followed by another empty read means it found nothing. An unrelated notification during
  the fetch could show `NO_DATA` early; the arriving data corrects it.
- **Header:** gets an empty snapshot while blank, so the old source's rain and icon do not stay under the new
  label, and the source button stays available. The temperature is the published value, which remains.
- **Tests:** `DesktopSourceWaitTest`; its three behavior tests failed before the change.
- **Live:** in an isolated offline copy with Silurian's rows removed, in the daily view: `LOADING` 0.5 s after
  the click, `NO_DATA` about 21 s later after the retries, and the source button switched away with
  `data_ready` at 502 ms. The hourly view's blank is covered by the Compose test.
- **Not exercised live:** the 3 s slow-load path, which needs a stalled load. The Compose test covers it.

## Related

`scripts/fast-desktop-restart.sh` relaunches the existing build, and its header points to
`build-exe-and-restart.sh`, which does not exist. Use `scripts/buildStart-desktop.sh` to rebuild. Not
changed here.
