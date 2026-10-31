# Desktop header: update the source label first, the rest when its data arrives

## Problem

On a desktop source switch the header mixes two sources for 0.5–1 s: the new source's label with the
previous source's daily rain chance and icon. The user saw a 40% flash.

Evidence from `HEADER_CONTENT` and `SOURCE_SWITCH_E2E` on 2026-10-31:

| Time | Label | Rain | Icon | Data on screen |
|---|---|---|---|---|
| 09:16:38.815 | Meteo | 40% | cloudy | Tomorrow.io's snapshot |
| 09:16:39.495 | Meteo | 1% | mostly clear | Open-Meteo's, `data_ready sinceSwitchMs=699` |
| 09:16:40.681 | Tmrw | 2% | cloudy | Open-Meteo's snapshot |
| 09:16:41.146 | Tmrw | hidden | partly cloudy | Tomorrow.io's, `data_ready sinceSwitchMs=476` |

## Root cause

1. A click updates `config` immediately, so `displaySourceId` is the new source.
2. `forecast` stays the old source's snapshot until the rebuilt repository's `loadCached()` returns.
3. `desktopHeaderContent` asks for the new source's hours in the old snapshot, finds none, and falls back
   to `today`, which is the old source's daily row (`DesktopWidgetHeader.kt:586`, `:593`).

The header treats `config` and `forecast` as always in step. They are not during a switch. This is a
different bug from the Android `WidgetInteractionCache` one fixed in `b0b72e79`, with the same symptom.

## Design

Each header part updates when its own data is ready:

- **Source label:** from `config`, immediately.
- **Everything else (rain, icon, night sizing):** computed for the source the snapshot actually holds,
  and replaced together when the new snapshot arrives.

Because the content is computed for the snapshot's own source, its hours are found and the wrong-source
fallback no longer happens. No part shows "?" or blinks, so the earlier "keep or hide the icon" question
goes away.

Unchanged: temperature and delta keep their current inputs (they do not come through the daily fallback),
and buttons and the home action stay on `config`.

### Accepted trade-off

Until the data arrives, the old source's real values stay beside the new label. Switching from Open-Meteo
to Tomorrow.io shows Open-Meteo's 1% under `Tmrw` for about 0.5 s, and the graph already behaves this way.
This matches the request ("update the source first and the rest later"). The value is the one already on
screen, unchanged, so nothing flashes.

## Changes

`:shared` owns the model, the header rule and the log lines. Desktop only supplies state.

### `:shared`

1. **`ForecastSnapshot.sourceId: String`:** required, with no default. The compiler then forces all three
   desktop construction sites and the 20 test sites to state their source, so no snapshot can skip it and
   no `?: displaySource` fallback branch exists to get wrong.
2. **`HeaderContent.build`:** add `labelSourceId: String = displaySourceId`, used only for `sourceLabel`.
   Android keeps the default, so its behaviour does not change.
3. **`HeaderContent.LOG_TAG` and `logLine(...)`:** move the `HEADER_CONTENT` line out of desktop's private
   `HeaderContentLog`, following `HeaderFit.LOG_TAG`/`logLine`. It names both ids, for example
   `source=NWS label=Tmrw`.
4. **`SourceSwitchRunner.dataReadyLine(...)`:** build the `phase=data_ready` line beside `E2E_TAG`, where
   the other phases are built.

### `:desktop`

5. **`DesktopWeatherRepository`:** pass `weatherSource` at its three `ForecastSnapshot(` sites, so no
   `forecast = …` assignment in `DesktopUiApplication` needs changing.
6. **`DesktopWidgetHeader`:** pass `forecast.sourceId` as `displaySourceId` and `displaySource.id` as
   `labelSourceId`. `HeaderContentLog` keeps only its once-per-change filter, since recomposition is the
   desktop-specific part.
7. **`DesktopSourceSwitch.onDataShown`:** take the snapshot's `sourceId` instead of relying on the comment
   that a rebuilt repository must hold the switched-to source.

### `:app`

8. **`DailyHeaderResolver`:** log `HeaderContent.logLine` beside the existing `HEADER_FIT` line at the same
   level, so one tag answers what the header showed on either platform. The 2026-10-31 Android 40% had no
   such line and had to be inferred.

### Docs

9. Note the rule in the living doc that covers desktop operation or headers.

### Left platform-specific, on purpose

- **`config` versus snapshot timing:** Android has no gap to manage, so a shared gate would have one user.
- **Row to `DailyFallback` mapping:** the two platforms read different row types (`ForecastEntity`,
  `DailyForecast`). A shared mapper would be a one-line wrapper on each side.
- **Wrong-source guard inside `HeaderContent`:** not needed once every caller passes the snapshot's own
  source. It would also need merging `fallbackDailyPrecip` into `DailyFallback`, which is a separate change.
- **Loading state:** repository and Compose state are platform glue.

## Tests

Each must fail on today's code where stated.

1. **`:shared` `HeaderContent`:** `labelSourceId` changes only the label; rain and icon follow
   `displaySourceId`. Default gives the same output as before. `logLine` names both ids.
2. **Desktop `desktopHeaderContent` (extends `DesktopHeaderIconTest`), fails today:** a Tomorrow.io
   snapshot with config on Open-Meteo gives label `Meteo`, rain hidden (Tomorrow.io's 0% hours), and
   Tomorrow.io's icon, never the daily 40%.
3. **Desktop integration on real SQLite, fails today:** switch source, hold the load, and assert the
   header before and after the snapshot swaps.
4. **Repository:** every returned snapshot carries the repository's source, including after a fetch.
5. **`:shared` `SourceSwitchRunner`:** `dataReadyLine` keeps today's format, so existing log queries hold.
6. **`:app`:** the daily header logs `HEADER_CONTENT` with the shown rain value.

## Verification

1. `./scripts/unit-tests.sh --fresh`.
2. Rebuild and restart with `scripts/buildStart-desktop.sh`.
3. Cycle every enabled source on the live desktop app. In the console log, each `HEADER_CONTENT` line
   during a gap must show the old source's values with the new label, and no rain value may belong to
   neither.
4. Confirm the header does not shift or rescale on a switch, per the existing reserve behaviour.

## Out of scope

- Shortening the 0.5–1 s gap. That is a separate look at repository rebuild and `loadCached` time.
- Android: it paints each switch as one complete frame, so it has no such gap.
- The Android-side daily fallback when a source has no hourly data at all.
