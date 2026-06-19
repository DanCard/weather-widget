# Daily icon: noon-cloud AND-gate + source-isolation parity & tests

## Context

Three related issues in the desktop daily forecast view, all rooted in the daily **icon**
resolution and **per-source** data discipline:

1. **"Partly cloudy" shows even when it's barely cloudy.** Today's NWS daily condition is
   worded "Partly Cloudy", but the noon hourly cloud cover is ~20%. The desktop daily icon
   is resolved purely from the worded condition text (`WeatherIcon.getIconResource(condition)`
   with `cloudCover = null`), so the measured cloud % never participates. Desired rule
   (user): a provider's "partly cloudy" is **necessary but not sufficient** — it must **also**
   be **≥25% cloudy at noon**, else downgrade to the slightly-cloudy tier ("mostly clear").
   Scope: **daily view only** (hourly icons untouched).

2. **The noon cloud % isn't API-specific on desktop.** Android's `resolveNoonCloudCoverRatio`
   filters hourly rows by the displayed source (`it.source == targetSourceId`); the desktop
   copy in `DesktopDailyForecastModel` does **not** filter by source.

3. **Source isolation must be guaranteed and tested everywhere.** Per user: data shown for a
   source must come only from that source — the **only** allowed exception is climate-normal
   fallback for future days when the API has no forecast (and that is shown prominently as
   `GENERIC`/`isClimateNormal`). Most desktop DAO queries already filter `AND source = ?`, but
   `getLatestObservation` filters only on location/time (the `observations` table has an `api`
   column it ignores) — a likely real leak. We want tests pinning isolation across the board.

Note: there is **no distinct "slightly cloudy" icon**. The non-rain tiers are
`clear → mostly clear (~18%) → partly cloudy (~35%) → mostly cloudy (~70%) → cloudy`.
"Mostly clear" is the slightly-cloudy tier and is what `getCloudCoverIcon` returns for 0–25%.

Threshold value (25%) is **not** changing (per user — the issue is bugs, not the threshold).

## Approach

Shared logic in `:shared`; both platforms delegate. Three workstreams.

### A. Source-filtered noon cloud %, shared (fixes #2)

Extract a single shared resolver and have both platforms call it:

- New `shared/.../util/DailyNoonCloudCover.kt`:
  `fun resolveNoonCloudCoverPercent(hourly: List<HourlyForecast>, date: LocalDate, targetSourceId: String, zone: ZoneId): Int?`
  — port of Android's `DailyViewLogic.resolveNoonCloudCoverRatio` **including the source filter**
  and the `GENERIC_GAP` special-case; returns the 0–100 percent (callers derive the 0–1 ratio).
- **Android** `DailyViewLogic.resolveNoonCloudCoverRatio` → delegate to it (keep the ratio wrapper).
- **Desktop** `DesktopDailyForecastModel.resolveNoonCloudCoverRatio` → delegate to it, passing the
  displayed source id (`displaySourceId`, already in scope in `buildDay`).

### B. Daily partly-cloudy AND-gate, shared, daily-only (fixes #1)

The gate must NOT live inside `resolveIconName` (that's shared with the hourly graph). Keep it in
the **daily** icon path on each platform, with the rule single-sourced in `:shared`:

- In `shared/.../util/WeatherConditionResolver.kt` add:
  - `const val PARTLY_CLOUDY_MIN_CLOUD_COVER = 25`
  - `fun applyDailyPartlyCloudyFloor(iconName: String, cloudCoverPercent: Int?, isNight: Boolean): String`
    → if `iconName` is `IC_PARTLY_CLOUDY`/`IC_PARTLY_CLOUDY_NIGHT` **and** `cloudCoverPercent != null`
    **and** `cloudCoverPercent < PARTLY_CLOUDY_MIN_CLOUD_COVER` → return `getCloudCoverIcon(isNight, cloudCoverPercent)`
    (i.e. "mostly clear"); else return `iconName` unchanged.
- **Desktop** `DesktopDailyForecastModel.buildDay`: resolve the daily icon **name** with cloud %
  threaded in, then apply the floor:
  ```kotlin
  val noonPct = DailyNoonCloudCover.resolveNoonCloudCoverPercent(hourly, date, displaySourceId, zone)
  val base = WeatherConditionResolver.resolveIconName(iconCondition, isNight, cloudCover = noonPct,
                                                      precipProbability = forecast?.precipProbability)
  val iconName = WeatherConditionResolver.applyDailyPartlyCloudyFloor(base, noonPct, isNight)
  ```
  Carry the resolved `iconName` on `DesktopDailyDay` (new field) and use it for both the painter
  (`WeatherIcon` map name→drawable) and `forecastColor`/`getConditionFlags`, replacing today's
  raw-`iconCondition` resolution in `DailyForecastGraph.kt`. (`isNight` for a daily noon icon = false.)
- **Android** `DailyForecastIconResolver.resolveIcon` (daily-only, already receives `cloudCover`):
  apply the same floor as a final step, mirroring the existing `shouldSuppressRainIcon`→`getCloudCoverIcon`
  pattern — when the resolved icon is partly-cloudy and `cloudCover < 25`, return
  `WeatherIconMapper.getCloudCoverIcon(isNight, cloudCover)`. Reuse the shared constant.

### C. Source-isolation audit + tests (fixes #3)

- **Fix the known leak:** add source/`api` scoping to `DesktopWeatherDao.getLatestObservation`
  (and any sibling observation query) unless observations are deliberately NWS-only — verify first
  against the schema + how non-NWS actuals are stored (see memory: Open-Meteo actuals backfill).
- **Tests** (shared module, in-memory SQLite — the DAO is already JDBC/SQLite and testable):
  new `DesktopWeatherDaoSourceIsolationTest` that seeds **two sources** at the same location/time
  and asserts each source-scoped getter (`getLatestHourly`, `getHourlyHistory`,
  `getDailyForecasts`, `getDailyForecastSnapshots`, daily actuals/extremes, observations) returns
  **only** the requested source's rows. Include the **allowed exception**: `getHourlyHistory`
  returns `Generic` rows only for `dateTime > now` (climate-normal future gap).
- **Test** `DailyNoonCloudCover`: given mixed-source hourly at noon, returns the requested
  source's value and never another source's.
- **Contract test** for the AND-gate (shared), pinning: partly + `<25` → mostly clear; partly +
  `≥25` → partly; partly + `null` → partly (unchanged); non-partly inputs untouched.

## Files

- `shared/.../util/DailyNoonCloudCover.kt` *(new)* — source-filtered noon %.
- `shared/.../util/WeatherConditionResolver.kt` — `PARTLY_CLOUDY_MIN_CLOUD_COVER` + `applyDailyPartlyCloudyFloor`.
- `app/.../widget/handlers/DailyViewLogic.kt` — delegate noon % to shared.
- `app/.../util/DailyForecastIconResolver.kt` — apply the daily floor.
- `desktop/.../DesktopDailyForecastModel.kt` — delegate noon %; resolve gated daily icon name.
- `desktop/.../DailyForecastGraph.kt` — use the resolved icon name for painter + colors.
- `shared/.../data/local/desktop/DesktopWeatherDao.kt` — source-scope `getLatestObservation` (pending verify).
- `shared/src/test/...` *(new)* — `DesktopWeatherDaoSourceIsolationTest`, `DailyNoonCloudCoverTest`,
  partly-cloudy floor contract test.

## Verification

1. `./gradlew :shared:test` (new isolation + gate + noon tests) and
   `./gradlew :app:testDebugUnitTest --tests "*DailyForecastIconResolver*"` green.
2. Confirm real data: query desktop `weather.db` for today's NWS daily `condition` and the noon
   `hourly_forecasts.cloudCover` (source = NWS) to confirm the 20% reading and the flip.
3. `scripts/buildStart.sh`; in the daily view, today should now show **mostly clear** (not partly
   cloudy) when noon NWS cloud < 25%, and stay partly cloudy at ≥25%. Toggle source (NWS ↔
   Open-Meteo) and confirm the icon/% track the displayed source only.
4. Sanity: hourly graph icons are unchanged (gate is daily-only).
