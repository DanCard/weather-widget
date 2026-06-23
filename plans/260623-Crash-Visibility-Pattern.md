# Adapt the Crash-Visibility Pattern to weather-widget

## Context

`notes/crash-visibility-pattern.md` is a project-agnostic hand-off note from the
cal-date-widget app describing how to answer "**will I know if users hit bugs in
production?**" before a Play Store launch. The note insists (Step 0) that the
conclusions be **re-derived per app**, not copied.

Re-deriving for this app:

- **Network/privacy posture (Step 0):** weather-widget already declares `INTERNET`
  + foreground/background location (`app/src/main/AndroidManifest.xml:5-9`). It is an
  *already-networked* app, so a network crash reporter (Crashlytics) is legitimately
  on the table — unlike the offline reference app. This is already wired correctly
  (conditional Crashlytics + local backup).
- **Gap analysis (Step 1):** most of the pattern was already implemented (2026-05-29
  hardening + today's `bbd05490`). Already present:
  - Chained uncaught-exception handler — `WeatherWidgetApp.installCrashLogger()`
    (`app/src/main/java/com/weatherwidget/WeatherWidgetApp.kt:50-67`)
  - Pure, testable formatting seam — `CrashReporter.formatCrashMessage`
    (`app/src/main/java/com/weatherwidget/util/CrashReporter.kt`) + `CrashReporterTest`
  - On-device persistence — CRASH-tagged rows in `app_logs` via `AppLogDao.log`
    (`app/src/main/java/com/weatherwidget/data/local/AppLogEntity.kt:94-116`)
  - User-initiated bug report surfacing the persisted crash — `BugReportActivity` /
    `AppLogsActivity` share path
  - Conditional Crashlytics + `-dontobfuscate` (`app/proguard-rules.pro:6`)

**This task therefore closes only the *real remaining gaps*** the note prescribes:
Step 2.5 (eliminate silent failures), Step 3 (integration test of the installed
handler), and a cheap Step 2.1 reinforcement. Intended outcome: a verifiable
guarantee that an uncaught crash is persisted and readable, and that the most
impactful permission/parse failures stop failing silently.

## Out of scope (deliberately)

- No new `CrashStore` file seam — `app_logs` already *is* the persistence + reader,
  and the note's CrashStore exists only to give offline apps a testable seam, which we
  already have via `CrashReporter` + `AppLogDao.getLogsByTag("CRASH", …)`.
- No "crash detected on restart" recovery UI — the note prescribes surfacing the crash
  in the *user-initiated* bug report (already done), not an automatic popup.
- No release-process items (Play Console `.aab` upload, Data Safety form, privacy
  policy URL) — these are operator actions, tracked in `plans/260529-privacy-policy-draft.md`,
  not code. (Noted in Verification as manual follow-ups.)

## Changes

### 1. Eliminate silent failures (note Step 2.5)

Add a `Log.w(...)` (with the caught throwable) to each swallow block so the failure
appears in logcat, the bug report, and — for ERROR-mirrored paths — Crashlytics.
Keep the existing fallback values; only add visibility.

- `app/src/main/java/com/weatherwidget/ui/MainActivity.kt:214-218` — location label
  resolution falls back to raw coords silently. **Highest impact** (a revoked/failed
  geocode silently shows numbers). Add `Log.w` with lat/lon + exception.
- `shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt:194-204` —
  `parseCurrentObservedAt` double-catches to `null` silently, hiding why a current
  observation has no timestamp. Use the `:shared` logger
  (`com.weatherwidget.shared.util.Log`, the pluggable one routed to logcat via
  `AndroidLogSink`) — **not** `android.util.Log`, which is invisible in the shared
  module (see memory `shared_log_invisible_on_android`). Log the raw `timeRaw` on the
  inner/final failure only.
- `app/src/main/java/com/weatherwidget/ui/BugReportActivity.kt:174-185` (DB size) and
  `:268-285` (app version) — both swallow to a placeholder. Add `Log.w`. Lower impact
  (diagnostics-about-diagnostics) but cheap and on-theme.

### 2. Integration test for the installed handler (note Step 3)

Add ONE Robolectric test asserting the *real installed* uncaught handler persists a
readable CRASH row — currently only the pure formatter is tested, so the
handler→store wiring is unverified.

- New: `app/src/test/java/com/weatherwidget/WeatherWidgetAppCrashHandlerTest.kt`
- Pattern: follow the existing Robolectric style in
  `app/src/test/java/com/weatherwidget/widget/handlers/WidgetIntentRouterCrashSafetyRoboTest.kt`
  (`@RunWith(RobolectricTestRunner::class)`, SDK 35).
- Approach: let `WeatherWidgetApp.onCreate()` install the handler (Robolectric builds
  the real Application), fetch `Thread.getDefaultUncaughtExceptionHandler()`, invoke
  `uncaughtException(Thread.currentThread(), RuntimeException("boom"))` wrapped in
  `try/catch(Throwable)` (persistence runs *before* the delegate, so the assertion is
  deterministic regardless of the delegate terminating), then assert a CRASH-tagged
  row containing "boom" reads back via `AppLogDao.getLogsByTag(CrashReporter.CRASH_TAG, …)`.
- Reuse the existing in-memory test DB convention (`TestDatabase.kt` /
  `IsolatedIntegrationTest.kt`) so the Hilt `Lazy<AppLogDao>` resolves to the test DB.
  Verify during implementation that Robolectric’s Hilt injection makes
  `appLogDao.get()` point at the same DB the assertion queries; if wiring the real
  `@HiltAndroidApp` Application is awkward, fall back to exercising the *same handler
  lambda* against a directly-constructed in-memory `AppLogDao` (keeps the
  handler→store contract under test without fighting Hilt).

### 3. Proguard reinforcement (note Step 2.1)

`proguard-android-optimize.txt` already keeps line metadata, but the note calls for it
explicitly alongside `-dontobfuscate` so readable `File.kt:NN` traces survive
regardless of the base file. Add to `app/proguard-rules.pro`:

```
# Keep source file + line numbers so crash traces stay readable (paired with -dontobfuscate).
-keepattributes SourceFile,LineNumberTable
```

(Single low-risk line; no behavior change to minification/shrinking.)

## Verification

- **Unit/integration:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.WeatherWidgetAppCrashHandlerTest" --tests "com.weatherwidget.util.CrashReporterTest"`
  — new handler test passes, existing formatter test still green.
- **Build:** `./gradlew installDebug` — confirms the silent-failure edits + shared
  logger usage compile (app + shared modules).
- **Release config sanity (optional):** `./gradlew assembleRelease` (needs keystore
  env) — confirms the new `-keepattributes` line is accepted by R8.
- **Manual on-device (note Step 4):**
  - Force a real uncaught crash, relaunch, open Bug Report / App Logs, confirm a
    readable `CRASH` row with a `File.kt:NN` trace.
  - Revoke location permission, trigger the GPS path in `MainActivity`, confirm the new
    `Log.w` fires (logcat) instead of a silent coord fallback.
- **Operator follow-ups (not code, tracked separately):** upload an `.aab` to Play
  Console so vitals stay deobfuscated; confirm Data Safety form + privacy policy
  (`plans/260529-privacy-policy-draft.md`) reflect crash data + location.
