# Crashlytics: NoClassDefFoundError for Hilt Generated Injector (26080301)

Date: 2026-08-05
Issue: https://console.firebase.google.com/u/0/project/fun-weather-widget/crashlytics/app/android:com.weatherwidget/issues/57766a787235b8ed53d9708df4d233d6

## Verdict

**Not a build bug — device-side corrupted/partial install.** The "missing" class is present in the
shipped artifact; nothing to fix in code or ProGuard config.

## Crash Signature

```
Fatal Exception: java.lang.NoClassDefFoundError:
  Failed resolution of: Lcom/weatherwidget/widget/WeatherWidgetProvider_GeneratedInjector;
    at com.weatherwidget.Hilt_WeatherWidgetApp.<init>(Hilt_WeatherWidgetApp.java:21)
    at com.weatherwidget.WeatherWidgetApp.<init>(WeatherWidgetApp.kt:19)
    ...
    at android.app.ActivityThread.handleBindApplication(ActivityThread.java:8260)
Caused by java.lang.ClassNotFoundException:
  Didn't find class "com.weatherwidget.widget.WeatherWidgetProvider_GeneratedInjector"
  on path: DexPathList[[zip file ".../base.apk"], ...]
```

Crash happens at process start (`handleBindApplication`), before any app code runs.

## Evidence

1. **Class IS defined in the shipped artifact.** Extracted
   `app/build/outputs/bundle/release/app-release.aab` (versionCode 26080301, the exact file fastlane
   uploaded 2026-08-03, commit `fcea8cee`) and ran `apkanalyzer dex packages` on
   `base/dex/classes.dex`:

   ```
   C d 0	0	36	com.weatherwidget.widget.WeatherWidgetProvider_GeneratedInjector
   ```

   `C d` = class **defined** in the dex. R8 did not strip it (`-dontobfuscate` + Hilt's consumer
   rules hold up). The bundle is single-dex, so no multidex corner case.

2. **The stack trace is a class-verification failure, not bad codegen.**
   `Hilt_WeatherWidgetApp.java:21` (generated source at
   `app/build/generated/hilt/component_sources/release/...`) is only the field initializer creating
   `ApplicationComponentManager` with an anonymous `ComponentSupplier`. Instantiating it makes ART
   load/verify that inner class, whose `get()` touches
   `DaggerWeatherWidgetApp_HiltComponents_SingletonC` — which references EVERY generated injector,
   including the provider's. When one of those can't be resolved from the on-device `base.apk`, ART
   throws at the triggering frame (`<init>` line 21). The trace is exactly what "class absent from
   the installed APK" looks like, even though it's in the AAB Play received.

3. **Toolchain is not a fresh suspect.** AGP 9.1.0 / Kotlin 2.3.10 / KSP2 / Hilt 2.59.2 landed
   2026-03-08 (commit `155ea0c2`) and shipped many releases without this crash.

## Most Likely Cause

Corrupted or partial install on the affected device. Classic triggers:

1. Process start **during a Play Store delta update** (package mid-replace).
2. Failed dexopt under storage pressure.

Widget apps are disproportionately hit because the launcher/AppWidgetHost starts the provider's
process (to bind the widget) even while the package is being replaced — matching the crash at
`handleBindApplication`.

## Recommended Action

1. In Crashlytics, check the issue's occurrence count, user count, and version breakdown:
   - Handful of crashes on 26080301 from one or two devices → install-corruption noise; close as
     not actionable.
   - Spike across many devices/versions → re-investigate.
2. No app-level fix exists — class resolution fails before any catchable code executes. Do NOT add
   ProGuard rules; they'd guard something already present.
3. If a specific device reproduces it every launch (e.g. a personal phone), uninstall + reinstall
   from Play clears the corrupted APK/dexopt state.

## Sidebar: Firebase CLI / fastlane access

Asked whether fastlane or the Firebase CLI could read the Crashlytics issue directly:

- fastlane setup is Play Store publishing only (`fastlane/Fastfile` has no Crashlytics lane), and
  `fastlane/play-store-api-key.json` belongs to a different GCP project
  (`personal-workspace-mcp-495506`) with no permission on `fun-weather-widget`.
- Firebase CLI Crashlytics support is upload-only (dSYM/mapping symbols); it cannot read crash
  issues. Installing it would not have helped.
- `gcloud` is installed but has no credentialed account; `gcloud auth login` + the
  `firebasecrashlytics.googleapis.com` REST API would be the programmatic path if ever needed.
