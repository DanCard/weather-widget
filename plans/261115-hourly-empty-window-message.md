# Blank hourly graph: say there is no data

Date: 2026-11-15. Report: "Fold-4 draw a blank graph. Shouldn't do that." User approved both parts
("implement both parts").

## Evidence (Fold 4, widget 392, NWS)

- `CLICK_DAILY date=2026-11-22 targetView=TEMPERATURE offset=165` at 14:43:45.
- NWS hourly rows end at `2026-11-22 00:00` (Google, Open-Meteo, Silurian reach 11-23 12:00).
- `TEMP_GAPS missing=19 noSelected=19 spans=[11-22 03:00..11-22 22:00]`; `hours=19`;
  `HOURLY_GAP_FILL action=Nothing`. No `CLICK_DAILY_NO_HOURLY` line: no banner.
- Screenshot: Sunday • NWS, footer 3a…7p, no line, no text.

## Root cause

The coverage rule counts the wrong hours.

- Android `NoHourlyDayClickCoordinator.hasHourlyForTappedDay`: **any** display-source row in the
  day's midnight-to-23:59 counts. The 00:00 row did.
- Desktop/shared `NoHourlyChecker.hasHourlyForDay`: **≥2 rows** in the day. Duplicate rows for one
  hour pass.
- The tap opens WIDE (18 h) centred on noon: 03:00–21:00. Neither rule looks at those hours.

## Change

### 1. Coverage = drawable hours in view (`:shared`)

- `NoHourlyChecker.hasHourlyForDay(hourly, date, sourceIds, view: HourSpan? = null)`: the day
  intersected with `view`, ≥2 **distinct hours** (a line needs two points). No view: the whole day.
  A day outside the view counts as covered (nothing of it is shown).
- `DayClickResolver.dayTapView(now, date)`: the WIDE window a day tap opens, from
  `calculateHourlyOffset` + `WeatherTimeUtils.hourWindow`.
- Android `hasHourlyForTappedDay` uses the shared rule (keeps the past-day observation fallback);
  tap, on-demand result, and pan pass their view. Desktop tap, fetch result, and pan pass theirs.

### 2. Notice on an empty graph (both platforms)

- `NoHourlyChecker.emptyViewDay(hourly, view, sourceIds, now)`: the day to name when the view starts
  at or after the current hour (so no observations can draw) and the source has <2 hours in it.
- Android: `HourlyEmptyNoticeRenderer` draws "No hourly forecast for {day} — data ends {end}"
  (existing localized strings) centred on the graph bitmap — temperature, precipitation, cloud.
  Precipitation's degenerate path (empty hours → keep old bitmap) renders the notice instead.
  Cloud's "Cloud data missing" diagnostic is skipped when the notice draws.
- Desktop: the same text centred over the hourly graph `Box`.
- Log `NoHourlyChecker.EMPTY_NOTICE_TAG` (`HOURLY_EMPTY_NOTICE`) with the shared `emptyNoticeLine`
  when drawn, both platforms (`CrossPlatformDuplicateLogTagsTest`).

### 3. A no-data tap stays on the daily view (user, mid-change)

User tested the old build: "Still showed empty graph with message instead of banner message." Asked
about the fetchable case: "Keep opening the graph".

- `DayTap.Outcome.NoData`: Android skips `setView` and pushes the banner (`FetchBanner.show`,
  `CLICK_TIMING branch=no_hourly_stay_daily`); desktop skips `onUpdateConfig`.
- `Outcome.Fetch` (on-demand fetchable) still opens the empty graph under "Fetching…".
- The on-demand result is judged on `dayTapView`, not the current view (desktop's callback held the
  pre-tap daily config).
- The on-graph notice waits while a banner with the same text (or "Fetching…") is up.

## Tests

- `NoHourlyCheckerTest`: Fold case (single 00:00 hour, duplicated rows) → not covered for the tap
  view; two hours inside view → covered; hours only outside view → not covered; no view → old
  whole-day behaviour with distinct hours. `emptyViewDay`: future empty → day; past view → null;
  covered → null.
- `DayClickResolver` test: `dayTapView` for a future day = 03:00–21:00.
- Fixtures whose "covered" day had points only at 00/12/23 now store realistic hours (one point in a
  03:00–21:00 view draws no line).
- Device (done 15:11): Fold tap on Sun Nov 22 stays daily with the banner; Sat opens; panning into
  Sunday logs `HOURLY_PAN NoDataMessage` then `HOURLY_EMPTY_NOTICE` and draws the notice on the
  precipitation graph (previously the degenerate path kept the old bitmap).
- `DailyFutureDayNoHourlyClickIntegrationTest` passes on the emulators; JVM suite 7,076 pass.
