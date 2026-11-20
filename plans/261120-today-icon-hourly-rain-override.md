# Today's daily icon: the current hour wins a clear rain or cloud conflict

## Request (user, 2026-11-20)

> If daily icon and hourly forecast conflict, use the hourly forecast derived icon? Only for the
> today column. This would mean that today icon could change as the hourly forecast changes.

## Evidence

emulator-5556, 2026-11-20 07:17, Tomorrow.io displayed:

- Today column: **sun icon** under **"8am 94%"**.
- `forecasts` row (fetched 07:14): `nativeDailyIconToken = 1000` (Clear), `precipProbability = 94`,
  `hourlyDayPrecipMax = 94`, `precipAmountMm = 7.6`, `noonCloudPercent = 3`.
- A direct `/v4/timelines` 1d call returned the same: `weatherCode 1000`, `precipitationProbability 94`,
  `cloudCover 100`. It is provider data, not a parse bug.
- Hourly rows: Rain 05:00 (15%), Rain 07:00 (35%), **Rain 08:00 (94%)**, Drizzle 09:00 (6%), then
  clear or mostly clear from 11:00 on. Tomorrow.io's daily code follows the dry part of the day, but
  its rain % is the day's maximum.

Same-day conflicts in the last 60 days (Samsung backup, rows for today whose hourly day maximum is at
least 30% while the daily condition is dry; this is a rough text match):

| Source | Conflicting rows | Max hourly % |
|---|---|---|
| Tomorrow.io | 11 (Clear 2, Cloudy 9) | 99 |
| OpenWeatherMap | 7 | 100 |
| WeatherAPI | 7 | 80 |
| Google | 7 | 63 |
| NWS | 2 ("… then Patchy Fog") | 85 |
| Open-Meteo, Silurian | 0 | — |

## Current behavior

`DailyColumnBuilder` (`:shared`, used by Android `DailyViewLogic` and desktop
`DesktopDailyForecastModel`) resolves each column's `iconName` through `DailyIconName.resolve`:

1. The provider's native token, or its condition text if there is no token.
2. A rain icon is replaced by a cloud icon when the day and night chances (from hourly rows) are both
   below the thresholds.
3. The partly-cloudy floor based on noon cloud %.

The hourly rows already correct one direction (a wet daily icon on a dry day, step 2). Nothing
corrects the other direction: a dry daily icon on a day the hourly forecast says is wet.

## Decisions (user, 2026-11-20)

- **Window: the current hour only.** The icon follows the hour we are in, so it varies hour by hour.
- **Threshold: 70%.**
- **Sources: all.**
- **Rain reads the header's rain %** (follow-up, 2026-11-20 07:4x): "The header value should cover the
  next hour, since that is what is set in settings" → "yes, switch to the header value". The emulators
  run `header_rain_lookahead_hours = 1`; the header's 97 % at 07:39 was the 8am peak inside that hour,
  while today's icon (current-hour row, 7am 0 %) showed a cloud.
- **Cloud rule added** (follow-up request: "add the cloud rule to the plan and implement it"), on
  the two-step terms proposed below.

## Proposed change (Claude proposals marked ◆)

1. **Scope: the today column only**, and only its main icon (`iconName`). The left "yesterday's
   forecast" icon, future days and past days are unchanged.
2. **Rain rule (revised):** today's icon turns rainy when the header's rain % is ≥ 70% — the same
   `PrecipProbabilityCalculator.maxPrecipProbabilityWithin` call over the same Settings → Header rain
   lookahead, on the display source's site rows — and the daily icon has no precipitation. The icon is
   the wettest hour's rain-type icon in that window (the window's rows plus the hour after it), at the
   header's chance tier: rain / storm / snow at ≥ 80%, "chance rain" at 70–79%; a wet hour worded dry
   resolves as "Rain". `DailyColumnBuilder.build(rainLookaheadHours)`, passed from
   `GraphDayRequest.rainLookaheadHours` (Android, `WidgetStateManager.getHeaderRainLookaheadHours`) and
   `DesktopSettings.headerRainLookaheadHours`.
   - emulator-5556 data, 1 h lookahead (7am 35%, 8am 94%, 9am 6%): "chance rain" from 06:37, rain
     06:47–08:00, "chance rain" to 08:16, then the cloud rule (8am 100% → cloudy).
   - Superseded first version: the current hour's own row at ≥ 70% (rain 08:00–08:59). It showed a
     cloud beside the header's 97% at 07:39.
3. **Applies at any hour of the day**, including night hours. ◆ After dark the rain icon replaces
   the night icon the same way, because the rain icons have no night variant.
4. **No rows:** with no hourly data for the source, the daily icon stays. A 3-hourly series reads rain
   as the header does (no interpolation pair → nothing), and its current row still feeds the cloud rule.
5. **Cloud rule** (applies only when the rain rule did not fire). The current hour's cloud % maps to an
   icon with the existing `getCloudCoverIcon` buckets (≤ 25 mostly clear, 26–74 partly, 75–90 mostly
   cloudy, ≥ 91 cloudy). The daily icon changes only when the two are two or more steps apart:
   - daily icon clear, clear night or mostly clear, and the hour is cloudy (≥ 91%): show cloudy;
   - daily icon cloudy or mostly cloudy, and the hour is mostly clear (≤ 25%): show mostly clear (or
     clear night after dark).
   Neighbouring icons (mostly clear and partly cloudy, for example) never switch, so the icon does not
   flicker every hour. Today on emulator-5556: cloudy 6–10am (98–100%), sun otherwise (18:00 at 90% is
   only mostly cloudy).
6. **Rain: one direction only.** A wet daily icon on a dry day is already turned into a cloud icon by the
   existing day/night threshold check. This change does not touch that path.
7. **Live behavior:** the column is rebuilt on every render from the stored hourly rows and `now`, so
   the icon follows the clock and each hourly fetch. ◆ No new repaint alarm: the icon changes at the
   next repaint after the hour turns (screen-on, current-temperature run, tap, fetch). During
   implementation I will confirm from logcat when a repaint follows an hour boundary on the emulator.
   If repaints turn out to be hours apart, I'll report back before adding anything. No schema change.
8. **Bar color follows the icon.** Both platforms color today's bar from its icon name
   (`WeatherColors.dailyForecastArgb`), so a cloudy override turns the bar grey and a rain override
   turns it rain-colored, matching the icon. (Corrects an earlier note in chat that the bar would not
   follow.)
9. **Logging:** `Log.v` `TODAY_ICON_HOURLY` with the source, daily icon, hour, chance and the icon
   chosen (VERBOSE, because it fires per render).

## Code

- `:shared` `DailyIconName`: add `todayCurrentHourOverride(dailyIcon, hourly, sourceId, lat, lon, now)`
  returning an icon name or null. It holds the whole rule: hour selection, threshold, icon choice,
  logging. Constant `TODAY_HOUR_RAIN_MIN_PERCENT = 70`; the cloud steps reuse `getCloudCoverIcon`.
- `:shared` `DailyColumnBuilder`: call it for `isToday` after `DailyIconName.resolve`, filtering
  `hourly` to the display source and site in the same way as the rain-label path.
- No platform changes: Android and desktop both take `iconName` from the shared column.

## Tests

- `:shared` unit tests for `todayCurrentHourOverride`, including the case seen on emulator-5556:
  Tomorrow.io token 1000 with `now` = 08:30 and hourly Rain 94% at 08:00 gives rain. It fails on
  current code. Also: 07:30 (Rain 35%) and 09:30 (Drizzle 6%) keep sun; exactly 70% flips and 69%
  does not; a precipitation daily icon is never touched; a storm hour gives storm; another source's
  hourly rows are ignored; no current-hour row keeps the daily icon.
- Integration (`:shared`): `DailyColumnBuilder.build` with a real forecast row and an hourly list
  produces a rain `iconName` for today at 08:30, sun at 09:30, and the unchanged icon for tomorrow.
- Integration (Android, Robolectric with a real Room DAO): insert the forecast and hourly rows, then
  check that `DailyViewLogic` produces the rain icon for today's column.
- Desktop: `DesktopDailyForecastModel` test using the same rows through the desktop SQLite host.

## Verification

- emulator-5556 (Tomorrow.io): screenshot during an hour with ≥ 70% rain shows a rain icon in today's
  column, with the logcat `TODAY_ICON_HOURLY` line. Today's rain hour will have passed by the time this
  ships, so the check may need a later rainy hour or a `now` override through the existing debug hooks
  if one exists; otherwise the automated tests carry it and I will say so.
- Desktop app restarted, showing the same icon for the same source.
- End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

## Docs

- `arch/ARCHITECTURE.md`: add a short "Daily column icon" note covering the three existing steps and
  this override (there is no living doc for the icon rule yet).
