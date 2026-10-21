# Sim loop: explicit errors, "Use Open-Meteo rain map" on every WeatherNext error

Plan: plans/261020-sim-loop-explicit-errors-open-meteo-fallback.md

- Trigger: the WeatherNext host (`ajoros.github.io/ai-weather-guy`) was taken down on 2026-10-20 (404 on
  every manifest, repo gone); the viewer said only "Couldn't load the rain loop".
- `:shared` `RainLoopLoadError` (kinds GONE, RATE_LIMITED, HTTP, TIMEOUT, OFFLINE, BAD_DATA, NO_FRAMES,
  OTHER; names the host), built from `RainLoopHttpException` (WeatherNext manifest/frames, Open-Meteo grid).
  WeatherNext treats 404/410 as GONE (`WeatherNextAvailability.loadError`); Open-Meteo never does.
- A 404 no longer hides the pill (user). For 24 h after one the viewer opens straight into the GONE
  message without a request (`WeatherNextAvailability.knownGone`; status kept beside the time).
- WeatherNext viewers (Android + desktop): centred panel with the explicit text, Retry (not for GONE),
  and "Use Open-Meteo rain map" (same coordinates; Android starts `OpenMeteoRainLoopActivity` and
  finishes, desktop flips `simLoopForceOpenMeteo`, reset on close — `simLoopOpensOpenMeteo`).
- Zero frames loaded → NO_FRAMES; partial loads still play. Frame downloads now reject non-2xx (a 404
  page had been written to disk as a frame).
- Open-Meteo viewers show the same explicit text (+ tap/click to retry).
- Logs: `SIM_LOOP_ERROR kind=… host=… status=… frames=… domain=…`, `SIM_LOOP_FALLBACK from=weathernext kind=… to=open_meteo`.
- 13 new strings × 20 locales; `sim_loop_load_failed` removed.
- Tests: `RainLoopLoadErrorTest` (client + classifier, MockEngine), `WeatherNextAvailabilityTest`,
  `RainForecastActivityRobolectricTest` (gone panel, fallback intent, retryable kinds),
  `RainLoopErrorRoutingTest` (desktop). Full suite: 5,639 passed.
