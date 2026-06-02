# Linux Desktop Weather Widget — Implementation Plan

## Context

We want a Linux desktop equivalent of the existing Android weather widget. Exploration
showed ~65–70% of the non-UI code is already nearly-pure Kotlin/JVM (all 7 API clients,
models, interpolators, accuracy stats) whose only Android dependency is `android.util.Log`.
The stack (Ktor, kotlinx.serialization, coroutines, java.time) runs unchanged on a desktop JVM.

**Decisions made with the user:**
- **UI:** JetBrains Compose for Desktop (Skia rendering ≈ Android Canvas).
- **Code sharing:** Shared module in **this repo**.
- **Form factor:** System-tray icon showing current temp + click-to-open popup with the graph.
- **Scope (v1 MVP):** Current temp + temperature graph only (NWS + Open-Meteo). Precip /
  cloud / daily / accuracy / settings come later.
- **DB boundary:** Do **not** share the repositories. Share only the clean layer; desktop gets
  its own thin orchestration + SQLDelight persistence. The Android app stays virtually untouched.

**Key architecture choice:** the shared module is a **plain `org.jetbrains.kotlin.jvm`
library**, not Kotlin Multiplatform. Both Android (JVM 21) and desktop run on the JVM, so KMP's
complexity (and its compatibility risk against AGP 9.1 / Kotlin 2.3.10) buys us nothing here.

## Target module layout (this repo)

```
weather-widget/
├── shared/     NEW  kotlin-jvm library: API clients, models, pure util, stats
├── app/             existing Android app — now depends on :shared
└── desktop/    NEW  Compose Desktop app: tray + popup, Skia renderer, SQLDelight
```

`settings.gradle.kts` currently includes only `:app` (line 18). Add `:shared` and `:desktop`.

## Step 1 — Create the `:shared` kotlin-jvm library

Plugins: `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.serialization`.
Dependencies: `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-json`,
`coroutines-core`, `serialization-json`. (No engine, no Android, no Room.) Target JVM 21.

**Add a `Log` shim** in the shared package so source edits are import-only:
```kotlin
package com.weatherwidget.shared.util  // new home
object Log { fun d(t:String,m:String){}; fun e(t:String,m:String,x:Throwable?=null){}; fun w(t:String,m:String){}; fun i(t:String,m:String){} }
```
(Back it with `java.util.logging` / SLF4J.) Each moved file then swaps
`import android.util.Log` → `import com.weatherwidget.shared.util.Log`.

**Move into `:shared`** (relocate, then make `:app` depend on `:shared` so it keeps compiling):
- `data/model/WeatherSource.kt`, `data/model/ForecastTypes.kt` (0 Android imports).
- `data/remote/NwsApi.kt`, `data/remote/OpenMeteoApi.kt`, `data/remote/ApiAccessException.kt`
  (Log only; constructors are `(httpClient, json)` — no API key / `WidgetStateManager` needed).
- `util/TemperatureInterpolator.kt`, `util/SpatialInterpolator.kt`, `util/ObservationBlender.kt`,
  `util/WeatherTimeUtils.kt`, `util/TempUtils.kt`, plus any of their pure helpers the compiler pulls in.
- `stats/AccuracyCalculator.kt`, `stats/AccuracyStatistics.kt` (0 Android imports).

**Decouple `TemperatureInterpolator` from Room:** it currently takes an `AppLogDao`. Change that
constructor param to a small `interface LogSink { fun log(tag:String,msg:String) }` (default no-op).
Android passes a Room-backed adapter; desktop passes no-op. This is the only invasive edit to a
moved file.

Defer the other 5 API clients (VisualCrossing / OWM / WeatherApi / Silurian / Tomorrow.io) — they
take `WidgetStateManager` for API keys and aren't needed for the MVP. Move them when those sources
are added, replacing `WidgetStateManager` with a small `ApiKeyProvider` interface.

After the move, `:app` builds against `:shared`; update its imports and have `di/AppModule.kt`
keep providing the `Android` Ktor engine + Room-backed `LogSink`/`AppLogDao` adapters.

## Step 2 — Build scaffolding for Compose Desktop

- Add the JetBrains Compose plugin + Kotlin Multiplatform-free Compose-Desktop setup to the version
  catalog (`gradle/libs.versions.toml`). Pick a **Compose Multiplatform version compatible with
  Kotlin 2.3.10** (verify against the official compatibility table — see Risks).
- `desktop/build.gradle.kts`: apply `org.jetbrains.kotlin.jvm` + `org.jetbrains.compose` +
  serialization; `compose.desktop.application { mainClass = "...MainKt" }`; package targets
  `Deb`/`AppImage` for Linux. Depends on `:shared`, `ktor-client-cio`, SQLDelight (sqlite driver),
  `kotlinx-coroutines-swing`.

## Step 3 — Desktop persistence (SQLDelight)

Define a minimal `.sq` schema covering only what the MVP needs: `hourly_forecast` (dateTime,
source, temperature, lat, lon) and `observation` (timestamp, source, temperature, lat, lon).
Mirror the column meaning of the Android `HourlyForecastEntity` / `ObservationEntity` so logic
ports cleanly. SQLDelight generates type-safe queries; store under `~/.local/share/weather-widget/`.

## Step 4 — Desktop orchestration layer (thin, desktop-only)

A small `DesktopWeatherService` (in `:desktop`) that wires the shared pieces — it does NOT reuse the
Android repositories:
- Construct `HttpClient(CIO)` with `ContentNegotiation`/`HttpTimeout` (copy the config block from
  `di/AppModule.kt:63-74`); build `NwsApi`/`OpenMeteoApi` from `:shared`.
- Fetch hourly forecast + latest observation, persist via SQLDelight.
- Resolve "current temp" with shared `TemperatureInterpolator` (no-op `LogSink`).
- A coroutine timer drives periodic refresh (start simple: fixed interval, e.g. 30 min; battery-
  aware tiers are an Android concern and out of scope for v1).
- Location: no GPS on desktop — read lat/lon from a config file (default **Google HQ**,
  37.4220, -122.0841), matching the Android default. Zip-code entry can come later.

## Step 5 — Port the temperature graph renderer to Skia

`widget/TemperatureGraphRenderer.kt` uses `android.graphics.*`: `RectF`×46, `Paint`×9, `Color`,
`Bitmap`×3, `Path`, `LinearGradient`, `DashPathEffect`, `Canvas`. All map to `org.jetbrains.skia.*`
(bundled with Compose Desktop via skiko). Create `desktop/.../TemperatureGraphRendererSkia.kt` as a
mechanical port:

| Android | Skia (org.jetbrains.skia) |
|---|---|
| `Canvas` / `Bitmap` | draw into Compose `Canvas` `drawScope.drawIntoCanvas { it.nativeCanvas }` (skia `Canvas`) |
| `Paint` | `org.jetbrains.skia.Paint` |
| `RectF` | `org.jetbrains.skia.Rect` (already float-based) |
| `Path` | `org.jetbrains.skia.Path` |
| `LinearGradient` | `Shader.makeLinearGradient(...)` |
| `DashPathEffect` | `PathEffect.makeDash(...)` |
| `Color` int | skia `Color`/`makeARGB` |

Keep the **algorithms** (Catmull-Rom smoothing, label collision avoidance, temp→color thresholds
from `TemperatureGraphStyle.kt`) byte-for-byte; only the drawing primitives change. The curve/label
math is the valuable part and is already Android-free. Render inside a Compose `Canvas` composable
rather than producing a `Bitmap`.

## Step 6 — Tray + popup UI

- System tray via Compose Desktop `application { Tray(...) }`. Tray tooltip/icon shows the current
  temperature (render a tiny text-to-icon, or update the tooltip string each refresh).
- Click → toggle a small frameless `Window`/popup (`undecorated = true`, sized like the widget)
  containing the `TemperatureGraphRendererSkia` Compose canvas + current temp label (top-left, 30sp
  equivalent) per the existing widget layout conventions.
- "Quit" item in the tray menu.

## Critical files

- Create: `shared/build.gradle.kts`, `desktop/build.gradle.kts`, desktop `Main.kt`,
  `DesktopWeatherService.kt`, `TemperatureGraphRendererSkia.kt`, `.sq` schema, `Log` shim.
- Move: model/remote(NWS,OpenMeteo)/util/stats files listed in Step 1.
- Edit: `settings.gradle.kts` (add modules), `gradle/libs.versions.toml` (compose + cio + sqldelight),
  `app/build.gradle.kts` (depend on `:shared`), `app/.../di/AppModule.kt` (LogSink adapter),
  `TemperatureInterpolator.kt` (AppLogDao → LogSink interface).

## Verification

1. `./gradlew :app:assembleDebug` — Android app still builds after the `:shared` extraction
   (proves no regression). Existing unit tests: `./gradlew :app:testDebugUnitTest`.
2. `./gradlew :shared:test` — add a couple of pure tests (e.g. `TemperatureInterpolator`) to prove
   the module is Android-free and runs on plain JVM.
3. `./gradlew :desktop:run` — launches tray; click opens popup. Confirm it fetches NWS/Open-Meteo
   for Google HQ, persists rows (inspect the SQLDelight db with local `sqlite3`), shows a current
   temp and a rendered temperature curve.
4. Visually compare the desktop curve against an Android screenshot of the same location/time to
   confirm the Skia port matches.
5. `./gradlew :desktop:packageDeb` (or `packageAppImage`) — produces a distributable Linux artifact.

## Risks / things to validate early

- **Compose Multiplatform ↔ Kotlin 2.3.10 compatibility.** Verify the exact CMP version against the
  JetBrains compatibility table before wiring the build; this is the highest-uncertainty item.
- **AGP 9.1 with added KMP-free Compose + Kotlin-JVM modules in one build** — keep `:shared` as plain
  kotlin-jvm (not the `kotlin.android` plugin) so the Android app can consume it without AGP on it.
- **Skia API drift** — skiko's `Canvas`/`Paint` signatures differ slightly from Android's; port the
  renderer in one focused pass and lean on a visual diff (verification step 4) rather than assuming parity.
- **No mocking framework** (per project convention) — desktop tests should follow the same pure-function
  approach already used in `:app`.
