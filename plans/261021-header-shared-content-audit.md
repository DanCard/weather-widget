# Header audit: what each platform computes itself

Follow-up to `261021-desktop-header-icon-from-shared-current-hour-rule.md` (done). Desktop header =
`DesktopWidgetHeader.kt`; Android = `DailyHeaderResolver` (daily), `TemperatureHeaderStateBuilder`,
`PrecipViewHandler`, `CloudCoverViewHandler` (hourly views).

## Field by field

| Field | Android | Desktop | Shared today? | Finding |
|---|---|---|---|---|
| Icon, daily | `HeaderConditionIcon` hour keys + selector | `HeaderConditionIcon` | yes (2026-10-21) | fixed |
| Icon, hourly views | `WeatherTimeUtils.getCurrentHourForecast`: display source, else GENERIC_GAP, **else any source's row** | `HeaderConditionIcon` (display source only) | no | **A1** Android can draw another source's condition — same class as today's bug |
| Icon, no hourly | `DailyForecastIconResolver.resolveIcon` (native token, partly-cloudy floor, rain suppression) | plain `resolveIconName(daily.condition)` | no | **A2** diverges when hourly is missing |
| Rain %, daily | `PrecipProbabilityCalculator.resolveHeaderPrecipitation` at now | same | yes | ok |
| Rain %, hourly views | `getNext6HourPrecipProbability` at the **panned centre hour** | `resolveHeaderPrecipitation` at **now** | partly | **A3** panning changes Android's %, not desktop's |
| Rain % shown when | `HeaderTapTargetHelper` `> 0` | `takeIf { it > 0 }` | no | duplicate, same today |
| Nowcast text | `NowcastDisplay` + `NowcastSummary` | same via `DesktopNowcastView` | yes | ok |
| Source label, hourly | `HeaderFormatter.formatSourceIndicator` → "Thu • Meteo" when panned off today | bare `shortDisplayName` | partly | **A4** desktop never names the panned day |
| Current temp | shared `CurrentTemperatureResolver` | same, via `CurrentStatus` | yes | ok (b1c82a88) |
| Temp text | `CurrentTemperatureResolver.formatDisplayTemperature` | `formatTrayTemperature` | no | duplicate, same output at ≥ 2 cols |
| Delta from yesterday | threshold 0.1 ×3 (`DailyHeaderBinder`, `TemperatureHeaderStateBuilder`, `TemperatureStateResolver`), °C `/1.8` inline | 0.1 + `/1.8` inline | no | **A5** four copies of one rule |
| Home action | `PreferredSourceHome.homeAction` | same | yes | ok |
| Today / obs in view | `NavigationUtils` range | rendered `dailyState.days` | no | equivalent; desktop's is from what is drawn |
| Date / buttons / "from yest" fit | `HeaderWidthChecker` | `DailyHeaderCentreFit`, Compose measure | no | platform layout — stays per platform |

## Proposal
One `:shared` `HeaderContent` builder for everything that is *content*, not layout: icon name, temp
text, delta text + visibility, rain % + font scale + nowcast text, source label, home action. Inputs:
hourly rows, today's daily row, current-temp resolution, delta from yesterday, view (daily / hourly +
centre time), now, lat/lon, units. Android's four header builders and `DesktopWidgetHeader` render from
it; width fitting stays per platform. Fixes A1–A5 by construction.

Decisions needed: A3 (rain % follow the pan, as Android, or now, as desktop?) and A2 (move
`DailyForecastIconResolver.resolveIcon`'s name logic into `:shared` — it is Android-only today).

## Tests
Equivalence: Android's current header outputs for the existing `DailyViewHandlerTest` /
`TemperatureHeaderStateBuilder` fixtures, through the shared builder. Discriminating cases for A1
(other source's row only at the hour), A3 (panned rain), A4 (panned label).

## Status 2026-10-21 (approved "proceed"; defaults: A3 follow the pan as Android, A2 move to :shared)
Not started in code — usage limit reached while reading. Found while reading:
- **A6** Android hides the delta in the precip and cloud hourly views (`current_temp_delta` GONE);
  desktop shows it in every hourly view.
- **A7** Android's precip view shows the rain % even at 0 % ("confirmation"); desktop hides 0 %.
- A2 detail: `DailyForecastIconResolver.resolveIcon` maps native tokens (OWM, WeatherAPI) straight to
  drawables; moving it means returning icon *names* (`WeatherConditionResolver.IC_*`) and Android
  mapping name→res. Desktop's daily column already has its own `gatedIconName` path — compare first.
- Hourly rain font: `getPrecipTextSize` = base × `DailyRainLabels.headerPrecipFontScale`, night scale
  daily-only, so desktop's hourly scale already matches.

## Done 2026-10-21 (session 2)
- `:shared` `HeaderContent.build` (icon, temp text, delta, rain %/text/scale, source label) — Android
  `DailyHeaderResolver`, `TemperatureHeaderStateBuilder`, `PrecipViewHandler`, `CloudCoverViewHandler`
  and desktop `desktopHeaderContent` all draw from it. Fixes A1 (display source only, no any-source
  fallback), A3 (hourly rain from the panned hour on desktop), A4 ("Thursday • Silur" on desktop),
  A5 (one `DELTA_VISIBILITY_THRESHOLD`), A6 (no delta in precip/cloud views on desktop), A7 (0 % shown
  in desktop's precip view).
- A2: `:shared` `DailyIconName` — Android `DailyForecastIconResolver.resolveIcon` is now name→drawable
  over it (existing `DailyForecastIconResolverTest` unchanged and passing); desktop's daily-view header
  fallback uses it.
- Removed desktop `DesktopHeaderPrecipitationResolver` (its cases moved to `HeaderContentTest`).
- Log: desktop `HEADER_CONTENT source=… view=… icon=… temp=… delta=… precip=… label=…` (console).
- Behaviour changes on Android: an hourly view whose source lacks the current hour now shows **no icon**
  (user, 2026-10-21) instead of another source's condition (A1); the daily view keeps today's daily
  row, then the unknown icon; the daily header's width fit measures the delta in
  display units (°C users), not °F.
- Verified live on desktop: daily `85%`, `+1.8 from yest`; hourly panned 27 h → `Thursday • Silur`, 75 %.

## A8 — not done (follow-up)
Desktop's **daily columns** still pick icons from the condition text
(`DesktopDailyForecastModel.gatedIconName`: `resolveIconName` + partly-cloudy floor), while Android's
columns use `DailyIconName` (native token, rain suppression when day and night chances are low). A
column can show rain on one platform and cloud on the other. Next: desktop columns call
`DailyIconName.resolve` with the same day/night chances and noon cloud %.
