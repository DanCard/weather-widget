# Desktop daily forecast view: rain info on/between bars (with code sharing)

## Context

Android's daily forecast view shows **two** rain labels per day column, which the desktop port is missing:

1. **Daytime label** — sits *on top of* each temperature bar (above the high-temp label).
   - Past days: observed amount (e.g. `2mm` / `.08in`) from `DailyExtreme.precipDayMm`/`precipAmountMm`.
   - Today: amount if day-prob ≥ 95% (and `precipAmountMm` known), else day-prob `%`.
   - Future: suppressed below a distance-scaled threshold (`4*daysFromToday + 1`), else amount if prob ≥ 99%, else prob `%`.
2. **Nighttime label** — tucked *between* columns near the bottom (shifted toward the next day's column), smaller font.
   - Past days: observed `DailyExtreme.precipNightMm`.
   - Future: night-prob `%` above the same distance-scaled threshold.

The desktop view (`DailyForecastGraph.kt`) currently draws only **one** crude label via a local `buildRainLabel()` that: uses *forecast* amount even for past days (never observed actuals), uses a different `>=25.4mm`→inches rule than Android, and has no day/night split or threshold gating.

This logic is pure (strings, numbers, dates) and is partially **duplicated** today: `formatPrecipAmount` exists in both `app/.../WidgetFormatUtils.kt` and `desktop/.../PrecipitationGraph.kt`. We will extract the pure rain-label logic into `:shared`, have Android and desktop both call it, and add the second (night) label + correct data wiring to desktop. Outcome: desktop daily view matches Android's rain display, with the text-building logic shared.

## Approach

### 1. New shared file: `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`

An `object DailyRainLabels` holding the pure logic (no Android types; uses `java.util.Locale`, `java.time`, shared `HourlyForecast`/`WeatherSource`):

- `data class DayNightPrecip(val dayMax: Int?, val nightMax: Int?)` — move from `DailyForecastIconResolver`.
- `fun getMinimumPrecipProbabilityDay(daysFromToday: Int): Int = 4 * daysFromToday + 1`
- `fun getMinimumPrecipProbabilityNight(daysFromToday: Int): Int = getMinimumPrecipProbabilityDay(...)`
- `fun formatPrecipAmount(amountMm: Float): String` — verbatim from `WidgetFormatUtils.formatPrecipAmount` (incl. private `formatInches`/`formatMillimeters`, US/GB→inches via `Locale.getDefault().country`).
- `fun calculateDayNightPrecipProbabilities(hourly: List<HourlyForecast>, targetDate: LocalDate, displaySourceId: String, fallbackSourceId: String = WeatherSource.GENERIC_GAP.id, zoneId: ZoneId = ZoneId.systemDefault()): DayNightPrecip` — port of the windowing math (8am–8pm day / 8pm–8am next-day night, max per window, source then GENERIC_GAP fallback) from `DailyForecastIconResolver.calculateDayNightPrecipProbabilities`, but on the shared `HourlyForecast` model.
- `fun buildDailyRainLabel(date, today, isPastDate, precipAmountMm, dayPrecipProbability, allowTodayRainChanceLabel, observedPrecipAmountMm): String?` — exact logic from `DailyViewLogic.buildDailyRainLabel` (drop the Android `Log` calls or use the shared `Log` shim at `shared/.../util/Log.kt`).
- `fun buildNightRainLabel(date, today, isPastDate, nightPrecipProbability, observedNightPrecipMm): String?` — from `DailyViewLogic.buildNightRainLabel` (note: Android passes `dailyRainLabelText` but never uses it — omit).

### 2. Android: delegate to shared (behavior-preserving)

- `app/.../util/DailyForecastIconResolver.kt`: `getMinimumPrecipProbabilityDay/Night` → call `DailyRainLabels`. Keep `DayNightPrecip` as a typealias or re-expose for existing callers. `calculateDayNightPrecipProbabilities(List<HourlyForecastEntity>, ...)` maps each entity to `HourlyForecast(dateTime, temperature, condition, precipProbability, source = it.source)` and delegates to the shared function (keeps `shouldSuppressRainIcon`/`resolveIcon` untouched).
- `app/.../widget/handlers/WidgetFormatUtils.kt`: `formatPrecipAmount` → delegate to `DailyRainLabels.formatPrecipAmount` (remove local `formatInches`/`formatMillimeters` if now unused; verify no other callers).
- `app/.../widget/handlers/DailyViewLogic.kt`: replace bodies of private `buildDailyRainLabel`/`buildNightRainLabel` with calls to `DailyRainLabels` (preserves call sites at lines ~533 and ~566).

### 3. Desktop: wire data + render both labels

- `desktop/.../DesktopDailyForecastModel.kt`:
  - Add `val dailyRainLabelText: String?` and `val nightRainLabelText: String?` to `DesktopDailyDay`.
  - In `buildDay`, compute via shared: derive day/night probabilities with `DailyRainLabels.calculateDayNightPrecipProbabilities(hourly, date, displaySourceId, ...)` for non-today (today keeps existing `nextPrecipProbability` 8h-max for the daytime value); build `dailyRainLabelText` (pass `observedPrecipAmountMm = actual?.precipDayMm ?: actual?.precipAmountMm`, `allowTodayRainChanceLabel = true` to match the widget) and `nightRainLabelText` (`observedNightPrecipMm = actual?.precipNightMm`). Need the display source id — thread `forecast`/config's active source (use the same source already used elsewhere in the model; if unavailable, fall back to the hourly rows' dominant `source`).
- `desktop/.../DailyForecastGraph.kt`:
  - Replace the local `buildRainLabel(day)` usage (lines ~170–178) with `day.dailyRainLabelText`; keep the existing "above the high-temp label, may bleed into header" placement.
  - Add nighttime label rendering: draw `day.nightRainLabelText` (when non-null) at `centerX + dayWidth/2` (clamped to canvas edges), in the low-label vertical band, ~0.72× font, in `COLOR_FORECAST_RAINY` — i.e. *between* this column and the next, matching Android's tuck. Skip if it would overlap the icon/day-name row.
  - Delete the now-unused local `buildRainLabel`.
- `desktop/.../PrecipitationGraph.kt`: replace local `formatPrecipAmount` (line ~617) with `DailyRainLabels.formatPrecipAmount` to remove the duplication (confirm the inch/mm output matches what that graph expects; it currently uses a fixed-decimals variant — unify on the shared one).

## Critical files

- New: `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`
- `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetFormatUtils.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopDailyForecastModel.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/PrecipitationGraph.kt`

## Reuse notes

- Threshold/format/windowing/label rules come straight from existing Android code — port, don't reinvent: `DailyForecastIconResolver` (lines 19–66), `WidgetFormatUtils` (lines 51–79), `DailyViewLogic.buildDailyRainLabel/buildNightRainLabel` (lines 622–710).
- `DailyExtreme` already exposes `precipDayMm`/`precipNightMm`/`precipAmountMm`; `DesktopDailyDay` already carries `actual`. No DB/schema changes.
- Desktop already follows the "shared math → platform draw" split (`DailyDayValueResolver`, `DesktopGraphUtils`); this extends it.

## Verification

1. **Unit tests (shared):** add `shared/src/test/.../DailyRainLabelsTest.kt` covering: past-day observed-amount path, today 95%/amount vs prob-fallback, future suppression below `4*d+1` threshold, night threshold gating, US vs metric formatting. Run `./gradlew :shared:testDebugUnitTest` (or `:shared:test`).
2. **Android regression:** `./gradlew testDebugUnitTest` — existing `DailyForecastIconResolverTest` and any rain-label tests must stay green (delegation is behavior-preserving).
3. **Desktop build + run:** `./gradlew :desktop:compileKotlin`, then per CLAUDE.md run `scripts/restart-desktop-distributable.sh` (auto-restart after a compiling desktop change). Open the daily view; confirm daytime labels sit on the bars and a smaller night label appears between columns. Compare against the running emulator widget (`adb exec-out screencap` → convert to JPG) for past days (observed amounts), today, and future days (probability gating).
4. Spot-check a past rainy day: desktop should now show the *observed* amount (from `precipDayMm`/`precipNightMm`), not the forecast amount.
