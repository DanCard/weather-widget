# Samsung: widget tap occasionally launches MainActivity — add diagnostic logging

## Context
On Samsung, occasionally tapping the widget pops up the main/intro screen
(`MainActivity`), which it should not. Investigation found:

- **No widget code path launches `MainActivity`.** The only Activity PendingIntents
  from the widget go to `SettingsActivity` / `WeatherObservationsActivity`
  (`app/.../widget/handlers/TemperatureTouchTargets.kt:291,332`). `MainActivity` is
  only the `MAIN`/`LAUNCHER` entry (`AndroidManifest.xml`).
- `widget_root` has **no catch-all PendingIntent** (`DailyViewHandler.kt:944` is just
  `setViewPadding`), so a tap on an area with no PendingIntent is never seen by our code.
- Most likely cause: **Samsung One UI Home falls back to launching the app's LAUNCHER
  activity when a widget tap misses every registered PendingIntent** (a launcher behavior;
  Pixel/AOSP treat such taps as no-ops). Cannot be confirmed from widget click handlers
  because the tap never reaches them.

The user wants **logging only** (low token budget) — confirm the mechanism the next time
it happens, no fix yet.

## Change
Add launch-provenance logging in `MainActivity` so a stray launch is recorded in the
`app_logs` DB table for later correlation with widget `CLICK_*` logs.

**File:** `app/src/main/java/com/weatherwidget/ui/MainActivity.kt`

1. Inject the existing logger:
   ```kotlin
   @Inject lateinit var appLogDao: com.weatherwidget.data.local.AppLogDao
   ```
   (`MainActivity` is already `@AndroidEntryPoint` and already uses `lifecycleScope`.)

2. Add a `logLaunchProvenance(reason)` helper and call it from `onCreate` (after
   `super.onCreate`) and from an added `onNewIntent` override. Use the existing suspend
   extension `AppLogDao.log(tag, message, level)` (see `data/local/AppLogEntity.kt`),
   launched on `lifecycleScope`. Tag: **`MAIN_LAUNCH`**.

   Capture (everything available without new permissions):
   - `referrer` = `referrer?.toString()` (the launching package — e.g.
     `com.sec.android.app.launcher` for a Samsung launcher fallback vs the home-screen
     icon; this is the key discriminator)
   - `intent.action`, `intent.categories`, `intent.flags` (hex), `intent.component`
   - `intent.extras?.keySet()` (e.g. presence of `appWidgetId` would be a strong signal)
   - `savedInstanceState == null` (fresh create vs recreation) and `taskId`
   - `reason` ("onCreate" / "onNewIntent")

   Mirror to `android.util.Log` happens automatically inside `AppLogDao.log`.

No behavior change, no UI change, no fix to the launch itself.

## Verification / how to debug next occurrence
- Build & install: `./gradlew installDebug`.
- Reproduce on the Samsung (tap widget dead zones a few times). When the main screen pops
  up unexpectedly, pull and query the DB (no sqlite3 on the Pixel/Samsung device — pull
  the file, query locally; `app_logs.timestamp` is epoch millis):
  ```sql
  SELECT datetime(timestamp/1000,'unixepoch','localtime') t, tag, message
  FROM app_logs
  WHERE tag IN ('MAIN_LAUNCH','CLICK_DAILY','CLICK_TIMING','TOGGLE_VIEW_TIMING','WIDGET_LIFECYCLE')
  ORDER BY timestamp DESC LIMIT 100;
  ```
- Expected confirmation of the theory: a `MAIN_LAUNCH` row whose `referrer` is the Samsung
  launcher package, `action=android.intent.action.MAIN` + `category LAUNCHER`, firing within
  ~1s of a widget tap — i.e. a launcher-fallback launch, not an in-app navigation.
- If instead `referrer` is our own package or extras carry `appWidgetId`, that redirects the
  investigation to a real in-app/PendingIntent path.

## Remedy (IMPLEMENTED)
Catch-all backstop on `widget_root` that absorbs dead-zone taps so Samsung's launcher can't
fall through to `MainActivity` — and shows a **"Dead zone tapped"** toast so the behavior is
visible/confirmable. Reuses the existing toast plumbing (`ACTION_SHOW_TOAST` /
`EXTRA_TOAST_MESSAGE` → `handleShowToastAction`, `WeatherWidgetProvider.kt:479`); no new action
or handler needed.

- `WidgetRequestCodes.kt` — added `BASE_DEAD_ZONE = 5000` + `fun deadZone(id)`.
- `TemperatureTouchTargets.kt` — added `setupDeadZoneCatchAll(context, views, appWidgetId)`:
  broadcasts `ACTION_SHOW_TOAST` with message "Dead zone tapped", bound to `R.id.widget_root`.
- Called right after `RemoteViews` creation in both render paths:
  `TemperatureViewHandler.kt` (~line 82) and `DailyViewHandler.kt` (~line 150).
  Partial updates merge with the last full update, so the root binding persists there.
- Loading/error placeholder states (`WidgetRenderer.kt`) deliberately left untouched — their
  comment relies on tap-to-recover behavior.

RemoteViews dispatches a click to the deepest view with a PendingIntent, so every existing touch
zone still wins; only unclaimed taps hit the root. Needs on-device verification on the actual
Samsung (history of touch-zone quirks there).
