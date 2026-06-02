# Session Log: Linux Desktop App (MVP) + Test Suite Fix
**Date:** June 2, 2026
**Status:** MVP complete and verified; follow-ups deferred

## Goal
Create a Linux desktop equivalent of the Android weather widget, deciding up front whether it
should live in this repo or a separate project, and what the key design decisions are.

## Design Decisions (agreed with user)
- **Location:** Same repo — a 3-module monorepo (one source of truth for weather logic).
- **UI:** JetBrains Compose for Desktop (Skia rendering ≈ Android Canvas).
- **Code sharing:** A shared module — but as a **plain `org.jetbrains.kotlin.jvm` library, not Kotlin
  Multiplatform**. Both Android (JVM 21) and desktop run on the JVM, so KMP adds complexity and
  AGP/Kotlin compat risk for no benefit.
- **Form factor:** System-tray icon + click-to-open frameless popup.
- **MVP scope:** Current temperature + temperature graph only (Open-Meteo). Precip/cloud/daily/
  accuracy/settings deferred.
- **DB boundary:** Do NOT share the Room-bound repositories; share only the clean layer (models +
  NWS/Open-Meteo clients). Desktop gets its own thin orchestration.

## Module Layout
```
weather-widget/
├── shared/    NEW  kotlin-jvm lib: data/model/*, NwsApi, OpenMeteoApi, ApiAccessException, Log shim
├── app/            Android widget — now depends on :shared, otherwise unchanged
└── desktop/   NEW  Compose for Desktop app: Main, DesktopWeatherService, TemperatureGraph
```

## What Was Built

### 1. `:shared` extraction
- Relocated the clean, closed set into `:shared` **keeping identical package names**, so `:app`'s
  imports never changed. Only edit inside moved files: swap `android.util.Log` → a new
  `com.weatherwidget.shared.util.Log` shim (java.util.logging-backed, mirrors the Android API).
- Files moved: `WeatherSource.kt`, `ForecastTypes.kt`, `ApiAccessException.kt`, `NwsApi.kt`,
  `OpenMeteoApi.kt`.
- `:shared` depends only on `ktor-client-core` (+ content-negotiation/json), coroutines,
  serialization, javax.inject. The HTTP engine is supplied per-consumer (Android engine in `:app`,
  CIO in `:desktop`).
- Fixed two cross-module smart-casts in `CurrentTempRepository.kt`: a nullable property from another
  module (`ForecastResult.currentTemp`) can't be smart-cast, so it's captured into a local val.

### 2. `:desktop` Compose app
- Verified the toolchain: Compose Multiplatform 1.11.x + `org.jetbrains.kotlin.plugin.compose`
  (lockstep with Kotlin 2.3.10) + AGP 9.1 coexist in one build. (Compose compiler ships as a Kotlin
  plugin since Kotlin 2.0; `org.jetbrains.compose` supplies runtime/tooling — the two-plugin split.)
- `Main.kt`: system tray (Show/Quit) + a frameless, always-on-top top-right popup.
- `DesktopWeatherService.kt`: `HttpClient(CIO)` (config mirrors `AppModule`) → shared `OpenMeteoApi`
  → returns the plain `ForecastResult`. Default location = Google HQ (matches Android).
- `TemperatureGraph.kt`: a Compose `Canvas` curve that reuses the Android widget's temperature→color
  thresholds (cold #5AC8FA ≤50°F, mild #E8A24E @70°F, hot #FF6B35 ≥90°F, blended) and Catmull-Rom
  smoothing. NOT a literal port of the 1273-line `TemperatureGraphRenderer` — its labels/overlays/
  multi-day logic are out of MVP scope.

### 3. Live run verified
Ran `:desktop:run` (this environment has DISPLAY=:0, network, and ImageMagick). Screenshot confirmed
the popup rendering live data for Google HQ: **60° / "Mostly Clear"** + a smooth, temperature-colored
hourly curve (warm peak orange, cool dip blue-grey). Proves the shared module is reused end-to-end.

### 4. Unit test suite fix (`AppLogEntity.kt`)
User reported "lots of tests failed." Investigation: ~46 of 1335 failed with
`IllegalStateException: Default FirebaseApp is not initialized`, thrown by
`FirebaseCrashlytics.getInstance()` inside the `AppLogDao.log` / `logException` / global helpers in
`data/local/AppLogEntity.kt`. Crashlytics auto-initializes via a Firebase ContentProvider only when
`google-services.json` is present; tests have no default FirebaseApp, so any logged INFO/WARN/ERROR
crashed the test in `runTest`. Confirmed pre-existing (reproduced on a clean HEAD worktree).
**Fix:** all four Crashlytics calls now route through a private best-effort `crashlytics { ... }`
guard that catches the exception and no-ops. Behavior unchanged when Firebase is initialized; DB +
logcat logging untouched. (Also fixed `VisualCrossingApiTest.kt`, which didn't compile on HEAD — it
used an old `apiKey=` constructor; updated to the `mockk<WidgetStateManager>` pattern used by sibling
API tests.)

## Verification Results
- `:shared:compileKotlin` — succeeds standalone (proves it's Android-free).
- `:app:compileDebugKotlin` — succeeds against `:shared`.
- `:app:testDebugUnitTest` — **1335 tests, 0 failures** (was 46 failing).
- `:desktop:compileKotlin` — succeeds; `:desktop:run` renders live temp + graph.

## Files
- New module `:shared`: `shared/build.gradle.kts`, `shared/src/main/kotlin/.../shared/util/Log.kt`,
  + the 5 relocated files (model/remote).
- New module `:desktop`: `desktop/build.gradle.kts`, `Main.kt`, `DesktopWeatherService.kt`,
  `TemperatureGraph.kt`.
- Modified: `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`,
  `app/build.gradle.kts`, `app/.../data/repository/CurrentTempRepository.kt`,
  `app/.../data/remote/VisualCrossingApiTest.kt`, `app/.../data/local/AppLogEntity.kt` (test fix).

## Git State at End of Session
- `ce33401` — "Extract :shared JVM module and scaffold :desktop Compose app" (the desktop-port work).
- `0254d31` — "Mirror app logs and exceptions to Firebase Crashlytics" (the AppLogEntity change that
  introduced the test failures; was uncommitted at session start).
- **Uncommitted:** the `AppLogEntity.kt` Crashlytics guard (the test-suite fix) — not yet committed.

## Deferred / Follow-ups
- SQLDelight persistence (needed for forecast-accuracy tracking; MVP fetches live).
- NWS source wiring (needs `NwsForecastMapper` ported off `Context`; `NwsApi` already in `:shared`).
- Full temperature-graph parity (high/low labels, "now" marker, forecast/accuracy overlays).
- Config-file location / zip-code entry.
- `packageDeb` / `packageAppImage` distribution.
- Minor: `compose.material3` accessor deprecation warning.
