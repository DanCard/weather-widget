# Share the NWS_BLEND row between Android and desktop

Plan: `plans/261002-share-nws-blend.md`

The synthetic `NWS_BLEND` observation is now built in one place, `:shared` `NwsBlend`, which returns
a row only when the IDW really blends something, and otherwise nothing.

## The bug it closes

When every NWS station was past the IDW's 3h decay, desktop still stored a blend row. In
`fetchNwsObservations` it carried the hourly forecast; in `fetchNwsObservationsOnly` it carried the
nearest station's stale reading. Android stored nothing in that case.

- A mocked desktop fetch with one 5h-old station wrote `NWS_BLEND=68.0` under the old code: the
  stale KNUQ reading saved as "the blend".
- The desktop DB's 93 retained blend rows (10 days) include none built this way. The bug is latent:
  it only fires during an NWS outage of 3h or more.

## Changes

- **New `shared/.../observations/NwsBlend.kt`.**
  - `latestUsableByStation`: each station's newest reading that passed QC; an existing blend row is
    never an input.
  - `build(...)`: the blend row, or null.
- **Android `CurrentObservationReader`** calls it. The old `latestUsableNwsObservationsByStation`
  helper is gone; its two tests in `CurrentObservationReaderTest` now exercise the shared function.
- **Desktop `fetchNwsObservations` and `fetchNwsObservationsOnly`** both call it. The header's
  current temperature (`providerCurrentTemp`) keeps its forecast / nearest-station fallback when
  there is no blend; that value is displayed only, never stored as an observation.
- **Desktop blend rows are now `stationType = BLENDED`** (was `VIRTUAL`), matching Android. Nothing
  reads that field.

## Tests

- **`NwsBlendTest`** (`:shared`, 6 cases): stale-only gives no row; no usable station gives no row;
  nearest station's condition, newest timestamp, IDW between stations; row filed at the storage key;
  QC filtering; a prior blend row is never an input.
- **`DesktopNwsBlendStaleTest`**: mocked NWS with one 5h-stale station gives no `NWS_BLEND` row.
  Fails on the old code, passes on the new.
- **Full Android, desktop and shared unit suites pass.**
- **`HardcodedUserFacingStringTest`** flagged the station name `"NWS Blended"` as unlocalized
  `:shared` prose. It was already hard-coded on both platforms, and both stations lists hide the
  blend row through `ObservationSourceMatcher`, so it is allowlisted with that reason.

## On-device check

- **Emulator:** widget renders normally; NWS current temperature 62.3°, columns unchanged.
- **Desktop:** after restart it wrote a real blend row at 00:43 (60.1°, `BLENDED`), right after
  the last old-style `VIRTUAL` row at 00:29.

## Notes

- **The first "passing" run against the old code was Gradle's cached result.** With
  `--rerun-tasks` it failed as it should. When swapping code under a test to prove it can fail,
  force the rerun.
- **The copies differed in the failure branch, not the maths.** One function makes "no blend, no
  row" the rule everywhere, and leaves each platform's header fallback with the caller, where it is
  never stored.

## Next candidate

The past-day forecast overlay bar: Android `DailyPastDayResolver.resolvePastDayOverlay` and desktop's
inline selection in `DesktopDailyForecastModel.buildDay` pick different forecasts.
