# A source that times out during Settings → Refresh data is never retried

## Evidence (desktop DB, 2026-11-13)

1. 09:57:04 Settings → Refresh data. NWS (displayed) refreshed first, then the others.
2. 09:57:39 `SETTINGS_REFRESH source=OPEN_METEO failed HttpRequestTimeoutException` (30 s client
   timeout). 09:57:47 `done refreshed=SILURIAN,OPEN_WEATHER_MAP,GOOGLE_WEATHER,TOMORROW_IO,WEATHER_API
   failed=OPEN_METEO`.
3. No `SOURCE_RETRY` row of any kind followed (not `scheduled`, not `skip`), no `SOURCE_RETRY_ARMED`.
   Open-Meteo's last fetch stayed 08:10:21; its next cadence fetch is `every=10h`. A direct curl of the same
   endpoint minutes later answered 200 in 0.11 s, so the timeout was transient, exactly what the
   1/2/5/10 min retry (`SourceRetry.DELAYS_MS`) exists for.

## Root cause (two layers)

1. **Never reported.** Desktop's all-sources path, `SettingsRefreshAllSources.refreshOthers`
   (`DaemonRuntime.kt` ~138), catches a failed source, logs `SETTINGS_REFRESH … failed`, and moves on. Only
   the single-source paths call `onForecastFetchFailed` → `SourceRetry.onFailure` (request, launch, loop).
2. **Would be skipped anyway.** `SourceRetry.onFailure` skips any source the host says is not displayed
   (`reason=not_displayed`). Open-Meteo was not the displayed source (NWS was). Android has the same gate
   (`ForecastFetchCoordinator.kt` ~486, "A displayed source that timed out…"): its all-sources forced sync
   would be skipped too.
3. **Desktop could not fire it.** The forecast loop runs a due retry only for the displayed source
   (`DaemonRuntime.kt` ~1378–1405, `activeSource`).

The rule was written for taps and the loop on a shown source (plans/261112-refresh-failure-reason-and-retry.md);
an explicit "refresh all" was not considered.

## Proposed change (shared rule, both platforms)

1. `SourceRetry.onFailure` gains an explicit-request input (e.g. `requested: Boolean`). A failure of a fetch the
   user asked for (Settings → Refresh data) is retried whether or not the source is displayed; background
   fetches of non-displayed sources keep the `not_displayed` skip. Battery/screen-off, transient-code and the
   4-attempt cap rules are unchanged.
2. Desktop: `SettingsRefreshAllSources.refreshOthers` reports each failure through a callback that calls
   `onForecastFetchFailed(sourceId, e, trigger = "request:settings", requested = true)`; the host's
   `isDisplayed` gate honours it.
3. Desktop loop: fire a due retry for any source with an armed row, not only `activeSource` (fetch through
   `repoFor(sourceId)` as the all-sources path does); consume/`shouldFire` semantics unchanged.
4. Android: pass the same flag where the forced all-sources sync records a failure; the delayed targeted
   sync is already per source.
5. Logging: `SOURCE_RETRY scheduled|skip|fire` lines name the source and `trigger=request:settings`.
6. Update `arch/hourly-forecast-update-cadence.md` ("Failed-fetch retry"): "displayed source, or any source
   during an explicit refresh-all".

## Tests

1. JVM (`:shared`): `SourceRetry.onFailure` with `requested=true` on a non-displayed source arms the retry;
   `requested=false` still skips `not_displayed`; battery-off still skips with `requested=true`.
2. `refreshOthers`: a timing-out source invokes the failure callback; the others still run.
3. Desktop: a due retry for a non-displayed source is fetched by the loop (host/loop seam test).
4. Verify on the desktop app: make one source time out during Settings → Refresh data (e.g. block its host
   briefly), confirm `SOURCE_RETRY scheduled attempt=1/4 delayMin=1`, then the fetch at 1 min. Android:
   emulator, same via logcat.

## Questions

- Retry only a Settings → Refresh data failure (proposed), or every transient failure of any source, shown
  or not (more wake-ups for sources nobody is looking at)?

## Implemented 2026-11-13 (scope approved: Settings refresh-all failures only)

1. `SourceRetry.onFailure(…, requested)`: a non-displayed source is retried when `requested`, or when it
   already has a live retry chain (so attempts 2–4 need no extra plumbing: a retry's own failure has
   `requested = false`). Battery/screen-off, non-transient and the 4-attempt cap are unchanged.
2. `SettingsRefreshAllSources.refreshOthers(…, onFailure)`: reports every failed source.
3. Desktop `DaemonRuntime`: the refresh-all passes `onFailure → onForecastFetchFailed(requested = true,
   wakeLoop = true)`; the forecast loop fires a due retry for any effective source (once-only, `shouldFire`,
   through its own repository) and sleeps toward the earliest armed retry of any source
   (`FORECAST_LOOP_SLEEP reason=SOURCE_RETRY next=<source>`).
4. Android `ForecastFetchCoordinator.safeFetch(requested = fetchContext.allSources)`; the delayed targeted
   sync already fetches one source.
5. `arch/hourly-forecast-update-cadence.md` "Failed-fetch retry" updated.
6. Tests: `SourceRetryTest` (requested non-displayed arms; unrequested still skips; chain runs 1/2/5/10 and
   caps; battery-off and non-transient still skip) — 3 fail on the old rule; `SettingsRefreshAllSourcesTest`
   (callback hears exactly the failed sources). JVM gate 6892 passed; `:app:assembleDebug` builds.
7. Not yet verified live: a real timeout during Settings → Refresh data (needs a blocked host) on desktop,
   and Android on an emulator.
