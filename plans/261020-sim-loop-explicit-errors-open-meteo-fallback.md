# Sim loop: explicit error messages, with "Use Open-Meteo rain map" on every error

## Trigger (2026-10-20 23:49, desktop, source = Silurian)
- The pill opened the WeatherNext viewer, and the manifest came back
  `WEATHERNEXT_FETCH outcome=gone domain=ca error=WeatherNext manifest HTTP 404`
  (`~/.local/state/weather-widget/autostart-20261020-221329.log:612`). At 18:01 it had loaded 100 frames.
- Checked at 23:5x: `ajoros.github.io/ai-weather-guy/{ca,pnw,wide}/manifest.json` and the site root all
  return 404, the repo `ajoros/ai-weather-guy` returns 404 from the API, and it is no longer one of the
  owner's 14 public repos. It was deleted or made private.
- The viewer printed only "Couldn't load the rain loop. Click to retry." That message doesn't say what
  failed, and Retry can't help when the site is gone.

## Request (user, 2026-10-20)
> Error messages should be explicit, with option to use open-meteo when there is an error.

## Root cause in our code
- Both viewers (desktop `RainForecastWindow`, Android `RainForecastActivity`) collapse every manifest
  failure into the same string (`sim_loop_load_failed`).
- Frame failures are silent: `cachedFrame(...).getOrNull() ?: continue`. If every frame fails, the
  viewer stays on its spinner or a blank map and shows no message at all.
- The Open-Meteo rain map (`OpenMeteoRainLoopWindow` / `OpenMeteoRainLoopActivity`) is reachable only
  when Open-Meteo is the displayed source.

## Design

### `:shared` — `RainLoopLoadError` (new, `shared/rainmap/`; as built it also serves the Open-Meteo map)
One classification, used by both platforms:

| Kind | From | Message (en) |
|---|---|---|
| `GONE` | manifest 404 / 410 | "The rain loop's host (ajoros.github.io) no longer serves it (HTTP 404). It may have been taken down." |
| `HTTP` | other non-2xx | "The rain loop's host (ajoros.github.io) answered HTTP 503." |
| `TIMEOUT` | Ktor `HttpRequestTimeoutException`, socket/connect timeout | "ajoros.github.io didn't answer in time." |
| `OFFLINE` | `UnknownHostException`, `ConnectException`, `UnresolvedAddressException` | "Couldn't reach ajoros.github.io — check the network connection." |
| `BAD_DATA` | JSON decode failure | "The rain loop's forecast list couldn't be read (unexpected format)." |
| `NO_FRAMES` | manifest OK, but this layer has no frames, or none of its frames downloaded | "None of the rain loop's {n} images downloaded." / "This model run has no images for this layer." |

- `fetchManifest` keeps returning `Result`; a new `WeatherNextLoadError.of(throwable)` does the
  classification, and the HTTP status travels on the exception (`WeatherNextHttpException(status)`,
  with `WeatherNextGoneException` as its 404/410 subtype).
- The host is taken from `BASE_URL`, not hard-coded twice.
- Log `SIM_LOOP_ERROR kind=… status=… domain=…` once per failure (replaces nothing; `WEATHERNEXT_FETCH`
  stays).

### Viewer UI (both platforms)
On any error the status area shows the explicit message plus two actions:
- **Retry**: hidden for `GONE`, since retrying a 404 within the session is pointless; shown for
  everything else.
- **Use Open-Meteo rain map**: always shown. It opens the Open-Meteo viewer for the same coordinates
  and closes the WeatherNext one. Logs `SIM_LOOP_FALLBACK from=weathernext kind=… to=open_meteo`.
- If some frames fail, the loop plays the frames that loaded. Only when **zero** frames load for the
  layer does it show `NO_FRAMES`.
- Desktop: `DesktopUiApplication` gets `simLoopForceOpenMeteo` state. The routing becomes
  `displaySource == OPEN_METEO || simLoopForceOpenMeteo`. It resets when the window closes, so the
  next tap on a WeatherNext source tries WeatherNext again.
- Android: `startActivity(OpenMeteoRainLoopActivity)` with `EXTRA_LAT/EXTRA_LON` copied from its own
  intent, then `finish()`.
- Strings: new Android strings in all 20 locales (locale parity test); desktop strings in the same
  place as its other viewer strings.

### The pill while WeatherNext is gone — **changed, needs your OK**
Today a 404 hides the pill for 24 h (`WeatherNextAvailability.GONE_HIDE_MS`). With that rule, a
Silurian/NWS/Google user can't reach the new error screen or its Open-Meteo button for a day.
**Proposal:** a 404 no longer hides the pill. The pill stays, and tapping it during those 24 h opens the
WeatherNext viewer straight into the `GONE` message with the Open-Meteo button, **without** another
request (the remembered `goneAtMs` is the answer). After 24 h, the next tap re-checks.
The off-map rule (WeatherNext doesn't cover the location) is unchanged.

## Tests
- `:shared` `WeatherNextLoadErrorTest`: each kind from a Ktor `MockEngine` client (404, 410, 503,
  timeout, unknown host, malformed JSON). This is an integration test: client + classifier.
- `:shared` availability: pill shown while gone; viewer-start state = `GONE` without a request inside
  24 h.
- Android Robolectric `RainForecastActivityRobolectricTest`: manifest 404 shows the explicit text, no
  Retry, an Open-Meteo button; tapping it starts `OpenMeteoRainLoopActivity` with the same extras and
  finishes. 503 shows Retry. All frames failing shows `NO_FRAMES`.
- Desktop: message/route tests in `:desktop` test (routing helper extracted from
  `DesktopUiApplication` so it is unit-testable).
- Run `./scripts/unit-tests.sh`, then `scripts/buildStart-desktop.sh`, open the pill on Silurian, and
  screenshot the error and the fallback.
