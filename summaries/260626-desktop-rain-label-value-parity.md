# Desktop/Android parity: today's daily rain-% label (2% → 15%)

Date: 2026-06-26

## Symptom

On the daily forecast view, the **today** column showed a different rain chance per platform:
Android **15%**, desktop **2%** — same location, same NWS data.

## Root cause

Both platforms already shared the formatter (`DailyRainLabels.buildDailyRainLabel`) and the
hourly-window calc (`calculateDayNightPrecipProbabilities`, 8am–8pm / 8pm–8am max). The drift was
in the **input selection** — which number gets formatted — which was reimplemented per platform:

- **Android** (`DailyViewLogic`): for an NWS row shown as NWS (`useDirectNwsPeriodPrecip`), it used
  NWS's native 12-hour **daytime period** chance directly (`daytimePrecipProbability ?: precipProbability`)
  = **15%**, skipping the hourly calc.
- **Desktop** (`DesktopDailyForecastModel`): had no direct-NWS branch — always
  `dayNight?.dayMax ?: forecast?.precipProbability`, i.e. the sparse **hourly** 8am–8pm max = **2%**,
  with a different fallback field.

## Fix — share the selection too

New pure function `DailyRainLabels.resolveDailyLabelPrecip(...) -> ResolvedDailyPrecip(dayPrecip, nightPrecip)`,
a faithful lift of Android's logic:
- NWS-as-NWS → `daytimePrecipProbability ?: precipProbability` (native period chance).
- else → hourly window max with period-field fallback.
- past days → period fields only (feed the icon; the label uses observed amounts).

Both platforms now delegate:
- Android via a thin `DailyForecastIconResolver.resolveDailyLabelPrecip` wrapper (text + graph paths).
- Desktop via `DesktopDailyForecastModel.buildDay`.

Carrying the NWS period precip end-to-end on desktop required:
- `shared/.../data/model/ForecastTypes.kt`: `DailyForecast` gained `source`,
  `daytimePrecipProbability`, `nighttimePrecipProbability`.
- `shared/.../data/remote/NwsDailyMapper.kt`: populate those from its day/night period maps.
- `shared/.../data/local/desktop/DesktopWeatherDao.kt`: `upsertForecasts` writes and
  `getDailyForecasts` reads the (already-existing) columns.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt` — `resolveDailyLabelPrecip` + `ResolvedDailyPrecip`.
- `shared/src/main/kotlin/com/weatherwidget/data/model/ForecastTypes.kt` — new `DailyForecast` fields.
- `shared/src/main/kotlin/com/weatherwidget/data/remote/NwsDailyMapper.kt` — populate period fields.
- `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt` — write/read columns.
- `app/.../util/DailyForecastIconResolver.kt` — Android wrapper; `app/.../widget/handlers/DailyViewLogic.kt` — delegate (both paths).
- `desktop/.../DesktopDailyForecastModel.kt` — delegate.

## Tests / verification

- `shared/.../DailyRainLabelsTest.kt`: 4 new cases incl. `directNwsUsesPeriodChanceOverSparseHourlyMax`
  (hourly 2% present but daytime period 15% wins) and the null-period fallback to `precipProbability`.
- Android `DailyViewLogic`/`DailyViewHandler` tests pass (behavior-preserving).
- All three modules compile; desktop rebuilt+restarted shows 15%.
- DB confirms the period columns now populate after a fetch (today `npp=15`, tomorrow `dpp=15`).

## Gotcha

NWS stops reporting today's **daytime** period once daytime is over, so `daytimePrecipProbability`
is null in the evening. The `?: precipProbability` fallback in the directNws path keeps today at 15%
regardless. (Recorded in the `shared_daily_rain_labels` memory.)
