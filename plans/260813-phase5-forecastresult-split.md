# Plan — Phase 5: split `ForecastResult` into `RawFetch` + `ResolvedView`

Date: 2026-08-13
Parent: `plans/260813-code-review-desktop-architecture.md` (finding **H2**)

## 1. Goal

Make the "raw fetch data" vs "resolved display state" conflation in `ForecastResult`
unrepresentable at the type level. Today one object carries both, which is exactly what allowed the
genmon/desktop temperature drift: `refreshObservations()` returned a `ForecastResult` whose
`rawObservations` (network list) disagreed with its own `currentTemp` (DB-resolved).

## 2. Current state

`ForecastResult` (`shared/.../data/model/ForecastTypes.kt`) mixes two concerns:

| Field | Kind | Who populates it |
|---|---|---|
| `daily`, `hourly` | raw | every API client |
| `rawObservations` | raw | API clients (historical-actuals backfill), desktop `loadCached`/`refreshObservations` |
| `dailyActuals`, `dailySnapshots` | raw (computed aggregates, not display) | desktop repository |
| `nwsDailyExtremes` | raw | NWS path |
| `currentTemp`, `currentCondition`, `currentObservedAt` | **ambiguous** | **both** the provider "current" object (`WeatherApi`, `OpenMeteoApi`, `TomorrowIoApi`, `OpenWeatherMapApi`, `VisualCrossingApi`) **and** the resolver (IDW blend / interpolation) |
| `appliedDelta`, `deltaFromYesterday` | resolved | resolver only |

The ambiguity is the defect: `currentTemp` means "provider's raw current reading" when written by an
API client and "resolved blend result" when written by the repository, with no way to tell which.

## 3. Target types

All three in `shared/.../data/model/` (next to `ForecastTypes.kt`).

```kotlin
/** Everything the network/DB produced. No display decisions. */
data class RawFetch(
    val hourly: List<HourlyForecast> = emptyList(),
    val daily: List<DailyForecast> = emptyList(),
    val rawObservations: List<ObservationReading> = emptyList(),
    val dailyActuals: Map<String, DailyHistory> = emptyMap(),
    val dailySnapshots: Map<String, List<DailyForecastSnapshot>> = emptyMap(),
    val nwsDailyExtremes: NwsApi.DailyTemperatureExtremes? = null,
    // Some providers return their own "current conditions" object. Carried here as raw input,
    // renamed provider* so it can never be mistaken for the resolved value.
    val providerCurrentTemp: Float? = null,
    val providerCurrentCondition: String? = null,
    val providerCurrentObservedAt: Long? = null,
)

/** What to display now, produced by the resolver (single writer). */
data class ResolvedView(
    val currentTemp: Float? = null,
    val currentCondition: String? = null,
    val currentObservedAt: Long? = null,
    val appliedDelta: Float? = null,
    val deltaFromYesterday: Float? = null,
)

/** Full snapshot: raw data + resolved display values, for consumers that need both. */
data class ForecastSnapshot(
    val raw: RawFetch,
    val resolved: ResolvedView,
)
```

`ForecastResult` is deleted at the end of the migration. Until then it can be kept as an
internal-only shim where a mechanical bridge is cheaper than editing a file twice.

## 4. Producer / consumer matrix

| Site | Today | After |
|---|---|---|
| `WeatherApi`, `OpenMeteoApi`, `TomorrowIoApi`, `OpenWeatherMapApi`, `VisualCrossingApi` | return `ForecastResult(currentTemp=provider, …)` | return `RawFetch(providerCurrent*=…)` |
| `SilurianApi` | returns `ForecastResult(daily, hourly)` | returns `RawFetch(daily, hourly)` |
| `NwsApi` | (assembled in service/repo) | unchanged — raw only |
| `DesktopWeatherService.fetchForecast/fetchObservationsOnly` | `ForecastResult` | `RawFetch` (+ existing `withHistoricalActuals` backfill moves to `rawObservations`) |
| `DesktopWeatherRepository.loadCached/refresh/refreshObservations` | builds `ForecastResult` with resolved fields | returns `ForecastSnapshot(raw, resolved)`; resolution stays in `resolveForForecastResult` |
| `CurrentStatusResolver` | takes `ForecastResult` | takes `ForecastSnapshot` (or `RawFetch` + `ResolvedView`) |
| Desktop `DaemonProcess.forecastState` | `ForecastResult?` | `ForecastSnapshot?` |
| Desktop `Main.kt` UI `forecast` | `ForecastResult?` | `ForecastSnapshot?`; header reads `snapshot.resolved`, graphs read `snapshot.raw` |
| Android `ForecastFetchCoordinator` | reads `.hourly/.daily` | reads `.raw.hourly/.raw.daily` |
| Android `CurrentTempRepository` | reads `.currentTemp/.currentCondition/.currentObservedAt` | resolves `RawFetch` → `ResolvedView` via the same shared resolver path |

## 5. Migration sub-steps (each independently shippable)

### 5a — Introduce the types + bridge (no behavior change)
- Add `RawFetch`, `ResolvedView`, `ForecastSnapshot` in `shared/.../data/model/`.
- Add `ForecastResult.toSnapshot()` / `RawFetch.from(ForecastResult)` mapping helpers so existing
  callers compile unchanged.
- **Verify:** `:shared:test`, `:desktop:test`, `:app:compileDebugKotlin`.

### 5b — Desktop repository returns `ForecastSnapshot`
- `loadCached()` / `refresh()` / `refreshObservations()` build `RawFetch` + `ResolvedView` and
  return `ForecastSnapshot`.
- `CurrentStatusResolver` switches to `ForecastSnapshot`.
- Daemon + UI update field access (`forecast.raw.hourly`, `forecast.resolved.currentTemp`, …).
- `ForecastResult` no longer used in the desktop module.
- **Verify:** `:desktop:test` full suite; runtime smoke (daemon publishes `current_status`, panel +
  popup agree).

### 5c — Shared API clients return `RawFetch`
- Change `WeatherApi`/`FetchOutcome` and the five provider clients to build `RawFetch`, renaming
  their "current" fields to `providerCurrent*`.
- `DesktopWeatherService` returns `RawFetch` (drop its `ForecastResult` references).
- **Verify:** `:shared:test` API-client tests, `:desktop:test`.

### 5d — Android migration
- `ForecastFetchCoordinator` reads `RawFetch.hourly/.daily`.
- `CurrentTempRepository` resolves `RawFetch` → `ResolvedView` (reuse the shared resolver; this is
  the Android half of H1 — single-owner resolution on Android too, if desired, or a thin local
  resolution matching today's behavior).
- **Verify:** `:app:testDebugUnitTest` + relevant Robolectric suites.

### 5e — Delete `ForecastResult`
- Remove `ForecastResult` and the 5a bridge helpers; fix any stragglers.
- **Verify:** full `:shared:test`, `:desktop:test`, `:app:testDebugUnitTest`.

## 6. Notes / risks

- **`providerCurrent*` rename is the crux.** It is what makes "raw vs resolved" legible. The five
  provider clients are the only places that populate those three fields from the network; everything
  else should treat them as resolver output.
- **`dailyActuals`/`dailySnapshots`/`nwsDailyExtremes` go to `RawFetch`** even though they are
  computed — they are *data*, not *current-display resolution*. Only the five `ResolvedView` fields
  are display state.
- **Order matters for reviewability:** 5b (desktop) before 5c/5d (cross-platform) means the
  desktop-only half of H2 lands first and can be reviewed in isolation, exactly like Phases 1–4.
- **Android single-owner resolution is optional** in this phase. The split only requires Android to
  consume `RawFetch` and produce a `ResolvedView`; whether Android also moves to a single persisted
  resolver (its own `current_status`) is a separate follow-up.
