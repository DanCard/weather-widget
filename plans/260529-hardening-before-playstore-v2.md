# Pre-Launch Hardening for Play Store

## Context

The user is considering a free Play Store launch of the weather widget but hasn't
heavily tested it and worries about bug reports. The real risk isn't the *volume*
of reports — for a niche free widget the likelier outcome is silent uninstalls and
1-star reviews. The real risk is that **the app currently has no way for the
developer to learn why anything broke** (no crash reporting of any kind), and a few
launch-grade liabilities exist (committed API keys, hardcoded keystore password,
paid-API keys shipping in the public APK).

This plan implements the **code-level** parts of the priority order. Items that
require the user's accounts (Play Console steps, privacy-policy hosting, rotating
keys on provider dashboards) are documented as follow-ups but cannot be done from
here.

### What I can implement vs. what needs you

| Priority | Item | Who |
|----------|------|-----|
| 1 | Crash reporting via **Firebase Crashlytics** (push) + local `CRASH` log/Share | **me** (code) |
| 1 | Create Firebase project + provide `google-services.json` | **you** (one-time) |
| 2 | Widget render-path hardening | **me** |
| 3 | Externalize keystore password + drop unused paid keys from release APK | **me** |
| 3 | **Rotate** the 5 leaked API keys (they're in git history) | **you** (provider dashboards) |
| 3 | `git rm --cached local.properties` if tracked | me (git), with your ok |
| 4 | Closed testing (12 testers / 14 days) + staged rollout | **you** (Play Console) |
| 5 | Privacy policy URL + Data Safety form (location **+ crash data**) | **you** |

---

## Priority 1 — Crash reporting (Firebase Crashlytics + local backup)

**Goal:** Every crash auto-uploads to a Crashlytics dashboard you watch (push), with
the existing `app_logs`/`AppLogsActivity` kept as an **offline backup** for users who
hit a crash while offline or who want to send you a fuller log.

**1a. Wire up Crashlytics** (Gradle)
- Add the Google Services + Crashlytics Gradle plugins. Project uses a version
  catalog (`libs.plugins.*`), so add entries to `gradle/libs.versions.toml` and
  apply in the root/`app` build scripts:
  - `com.google.gms.google-services`
  - `com.google.firebase.crashlytics`
- Add the Firebase BoM + `com.google.firebase:firebase-crashlytics` (and
  `firebase-analytics`, which Crashlytics expects) to `app/build.gradle.kts`
  dependencies.
- **USER ACTION:** create a Firebase project for `applicationId = com.weatherwidget`
  and drop `google-services.json` into `app/`. Add it to `.gitignore` (it contains
  project identifiers). The build will fail to assemble without it once the plugin is
  applied — so I'll gate/document this clearly.
- Crashlytics installs its own uncaught handler automatically; no manual
  `Thread.setDefaultUncaughtExceptionHandler` needed for upload.

**1b. Local CRASH log as offline backup** — `WeatherWidgetApp.kt`
- Still install a lightweight handler that writes a `"CRASH"`/`"ERROR"` row to
  `app_logs` (thread name + `Log.getStackTraceString(throwable)`), then **delegates to
  the previously-registered handler** (which is now Crashlytics' — so we chain rather
  than replace, preserving auto-upload).
- Persist synchronously (`runBlocking { withTimeoutOrNull(2000) { ... } }`) wrapped in
  try/catch so a failed write can't worsen the crash; fall back to `Log.e`.
- DB access: `@Inject lateinit var appLogDao: AppLogDao` (Hilt injects Application
  members during `super.onCreate()`, as already done for `workerFactory`). If only
  `AppDatabase` is provided in the graph, inject that and call `.appLogDao()`.
- Optionally mirror non-fatal repository errors to
  `FirebaseCrashlytics.getInstance().recordException(e)` at the existing catch sites
  (e.g. `ForecastRepository`, `CurrentTempRepository`) — low effort, high signal.

**1c. Share/export logs** — `AppLogsActivity.kt`
- Add a "Share logs" action that dumps `getRecentLogs(...)` to text and fires an
  `ACTION_SEND` chooser (`type=text/plain`, `EXTRA_TEXT` = dump, optional
  `EXTRA_EMAIL` = developer address). Backup path for offline crashes.
- For large dumps prefer a cache file shared via `FileProvider` if one already exists
  in the manifest; otherwise `EXTRA_TEXT` is the simpler v1.

**Tests:** plain-JUnit test that the local handler formats a `CRASH` log line from a
throwable and **chains to the prior handler** (extract trace-formatting + persistence
into a small injectable function so it's testable without a dying process —
consistent with the repo's "pure function extraction" testing strategy). Crashlytics
upload itself is not unit-testable; verify it manually (see Verification).

---

## Priority 2 — Widget render-path hardening

**Goal:** No single bad API response or unforeseen exception can leave a blank /
"Problem loading widget" box on the home screen.

- `WeatherWidgetProvider.kt` — audit `onUpdate()` and the `RemoteViews` build/bind
  path. The async work in `launchAsync()` already has a try/catch (~lines 811-820);
  ensure the **synchronous** portion of `onUpdate()` and any
  `appWidgetManager.updateAppWidget(...)` binding is also wrapped, so a throw there
  can't escape into the launcher. On failure, bind a minimal fallback view
  (cached values or a "tap to refresh" state) rather than letting it propagate.
- Apply the same guard to the `WidgetIntentRouter` tap paths (per memory, those are
  the code paths Samsung actually exercises).
- **`!!` cleanup (clarity, not bug fixes — all three are currently guarded):**
  - `ObservationRepository.kt:279` — use a local `val` from the `isNullOrBlank`
    guard so the `!!` disappears via smart-cast.
  - `ObservationRepository.kt:584` — `maxByOrNull{}!!` is safe (groupBy invariant);
    leave a brief comment or use `.values.first()` equivalently. Optional.
  - `NwsForecastMapper.kt:389` — restructure the `if` so `temps.second` smart-casts
    to non-null. Optional.

**Tests:** add a `WeatherWidgetProvider` test (Robolectric) that a thrown exception
during bind results in a fallback `RemoteViews` rather than an escaped exception.
Reuse the `reapply()` test pattern noted in memory for sticky-visibility safety.

---

## Priority 3 — Secret & release-APK lockdown (code parts)

**3a. Externalize keystore password** — `app/build.gradle.kts:102-109`
- Replace the literal `"password123"` for `storePassword`/`keyPassword` with reads
  from `local.properties` (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`) falling
  back to `System.getenv(...)`, mirroring the existing API-key pattern at lines
  21-50. Keep the build working when unset (debug builds don't need it).

**3b. Drop unused paid-API keys from the release APK** — `app/build.gradle.kts`
- Release source list (`WidgetStateManager.kt:76`) is `NWS, OPEN_METEO, SILURIAN`.
  NWS/Open-Meteo need no key. So in the `release { }` build type, override the four
  unused `buildConfigField`s to empty:
  `WEATHER_API_KEY`, `OPEN_WEATHER_MAP_API_KEY`, `VISUAL_CROSSING_API_KEY`,
  `TOMORROW_IO_API_KEY` → `"\"\""`. Keep `SILURIAN_API_KEY` (it ships in release).
- **Verify during implementation** which sources are actually reachable in a release
  build before blanking, so we don't strip a key something needs.

**3c. Stop tracking secrets in git** (with user ok)
- If `local.properties` is tracked, `git rm --cached local.properties` and confirm
  it's in `.gitignore`. Note: removal from HEAD does **not** scrub history — the keys
  are already exposed, which is why rotation (below) is mandatory, not optional.

**3d. Rotation — USER ACTION (cannot do from here)**
- Regenerate all five keys on their dashboards (WeatherAPI, Silurian, OpenWeatherMap,
  Visual Crossing, Tomorrow.io) and put the new values in `local.properties` /
  CI env. The old keys must be considered compromised.

---

## Priority 4 & 5 — Play Console & paperwork (USER ACTIONS, documented only)

- **Closed testing**: new personal Play accounts must run a closed test with 12+
  testers for 14 continuous days before production — this *is* your soak test for the
  "haven't tested heavily" concern. Then promote with a **staged rollout** (start
  ~10-20%) to cap blast radius.
- **Privacy policy URL** (mandatory because of location) + **Data Safety form**
  declaring location collection and that lat/lon is sent to third-party weather APIs.
- $25 one-time registration; `targetSdk 34` already meets current requirements.

---

## Verification

1. **Build**: `./gradlew installDebug` (debug must build with the externalized
   keystore reads unset). Then a release assemble to confirm signing still resolves
   when `RELEASE_*` props are present: `./gradlew assembleRelease`.
2. **Crash reporting**: trigger a deliberate throw (temporary test hook or debug
   menu action). Confirm (a) a `CRASH` row appears in `AppLogsActivity`, (b) the
   system crash dialog still appears (chained to Crashlytics), and (c) the crash lands
   in the Firebase Crashlytics dashboard within a few minutes (note: Crashlytics
   uploads on the *next* app start, so relaunch once). Then exercise "Share logs" and
   confirm the chooser opens with the trace. Confirm the build fails clearly if
   `google-services.json` is absent.
3. **Release key stripping**: `./gradlew assembleRelease`, then inspect the built
   `BuildConfig` / APK to confirm the four unused key fields are empty and Silurian
   is present. (`unzip -p` the APK + `strings`, or check the generated `BuildConfig`.)
4. **Widget hardening**: unit/Robolectric tests above; manually, install and resize
   the widget (1x1, 1x3, 2x3) and confirm no blank/error box under a forced bad
   response. Per CLAUDE.md, pull logs/screenshots (`adb logcat`, screencap→jpg) to
   confirm behavior on-device rather than only reading source.
5. **Unit tests**: `./gradlew testDebugUnitTest` and, if instrumented coverage is
   touched, `./scripts/emulator-tests.sh` (never `connectedDebugAndroidTest`).
