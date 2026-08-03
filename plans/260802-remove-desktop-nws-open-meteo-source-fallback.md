# Remove the desktop NWS→Open-Meteo SOURCE_FALLBACK

Status: **implemented 2026-08-02**

## Problem

`DesktopWeatherService.fetchForecast`'s `getOrElse` exempted NWS from the no-masquerading rule:

```kotlin
// NWS has no coverage outside the US, so Open-Meteo is the intended substitute there.
"NWS" -> {
    weatherDao?.log("SOURCE_FALLBACK", "NWS unavailable, substituting Open-Meteo: ${e.message}", "WARN")
    fetchOpenMeteoForecastWithActuals()
}
```

The stated rationale is about **geography**, but the `getOrElse` catches **every** exception. So
transient network failures substituted too. Actual log evidence at a US location NWS covers fine:

- **222 `SOURCE_FALLBACK` events**, 5–14 per day through mid-July.
- Every message is a transient error, never a coverage problem:
  `Channel was closed`, `Request timeout has expired`,
  `Invalid chunk: content block of size 49152 ended unexpectedly`, and `null`.

Each event wrote Open-Meteo forecast temperatures into `observations` labeled `api='NWS'`,
`stationId='NWS_MAIN'`, `distanceKm=0` — which then hijacked the actual-temperature blend. See
[260802-desktop-nws-main-backfill-hijacks-blend.md](260802-desktop-nws-main-backfill-hijacks-blend.md);
that fix stopped the rows from *corrupting the blend*, this one stops them being *minted*.

### Two decisive facts

1. **Desktop-only.** `SOURCE_FALLBACK` exists nowhere in `app/` or `shared/` — Android has never
   substituted sources at fetch time, and has zero `NWS_MAIN` rows to show for it.

2. **The coverage case is already detected precisely, and handled elsewhere.**
   `NwsApi` throws `NwsPointUnavailableException` **only** on a 404 from `/points` that passes
   `isUnsupportedPointProblem` — a genuine "NWS does not cover this point" signal, distinct from any
   transient error. Android acts on exactly that distinction at **source-selection** time:
   `SetupSourceAvailabilityChecker.checkNws` returns `UNSUPPORTED` for that exception but
   `INCONCLUSIVE` for timeouts, network errors and HTTP failures — so a transient error never
   changes the user's source. The blanket desktop catch collapsed all of those into "substitute".

## What changed

1. **`DesktopWeatherService`** — deleted the `"NWS" ->` branch. NWS now falls into the same `else`
   branch as every other explicitly-selected source: log `SOURCE_ERROR` and rethrow. The refresh
   loop then logs `REFRESH_FAIL` and calls `deriveDataStatus`, so the UI shows cached data with a
   staleness indicator — matching CLAUDE.md's error-handling contract instead of silently showing
   another provider's numbers under the NWS badge.

2. **`WeatherSource.requiresApiKey`** (new shared property) — NWS reaching the `else` branch exposed
   a latent bug there: the hint condition was `!hasKey && source != OPEN_METEO`, which would have
   told the user *"no API key configured for NWS — set it in local.properties or Settings"*. NWS is
   keyless. The property names the five keyed sources in one place; desktop's hint and Android's
   `ApiSourceWarningHelper` (which had its own private copy of the list) now both read it.

## Verification

- `:desktop:compileKotlin`, `:shared:test` (616), `:app:testDebugUnitTest` (1761) — all green.
- Confirmed by inspection that `fetchOpenMeteoForecastWithActuals` is now reached only when
  Open-Meteo is the selected source (or the id is unrecognised), so its display-source labeling is
  a no-op rather than the NWS-relabeling path.

## Follow-ups

- Consider a bounded retry for transient NWS errors. Removing the fallback means a `Channel was
  closed` now costs a refresh cycle rather than silently substituting; the fallback events largely
  stopped after 2026-07-17, so the current rate is low, but a retry would make it lower.
- If a non-US location is ever used on desktop, `NwsPointUnavailableException` should surface a
  clear "NWS does not cover this location — pick another source" message in Settings, mirroring
  Android's setup-time check, rather than silently substituting.
