# Samsung Package-Update Widget Rebind

## Status

Implemented and verified on the Samsung SM-F936U1 on 2026-08-04.

## Runtime Evidence

1. The affected host is the physical Samsung SM-F936U1 on API 36, not the API-36 emulator.
2. At 21:45:35 an ADB package replacement force-stopped `com.weatherwidget`.
3. One UI immediately delivered `remoteViews: null` to active widget IDs 345 and 352 and inflated
   the provider's XML default layout. The visible result was the sparse `Today / --deg / --deg`
   shell instead of the daily graph.
4. No `MY_PACKAGE_REPLACED`, `APPWIDGET_UPDATE`, provider process start, or app-side repaint followed
   the replacement. The provider was still registered and the launcher host callback was live.
5. Starting the app and issuing the existing cache-first refresh rendered valid data. The first
   complete-body partial was promoted to a full push with
   `fullThisProcess=false unbackedPartial=true promoted=unbacked_partial`; One UI accepted it and
   `dumpsys appwidget` again reported backed `RemoteViews` for IDs 345, 349, and 352.
6. The final screenshot showed the complete ten-day graph at the correct One UI size of 574x401 dp.

## Root Cause

Commit `6788d009` correctly removed internal widget commands from the exported provider surface,
but also changed `WeatherWidgetProvider` itself from exported to non-exported while leaving
`MY_PACKAGE_REPLACED` on that receiver. Emulator add/update/resize/delete tests passed, but Samsung
did not deliver the package-specific system broadcast to the non-exported provider after an APK
replacement.

The daily and hourly rendering gates are not the missing fix. They already force a complete body on
the first UI-only cycle of a fresh process. In the observed incident, no fresh process or cycle was
started until the app was opened manually.

Android documents `ACTION_MY_PACKAGE_REPLACED` as a protected, system-only broadcast delivered to
manifest receivers in the replaced package. Android's broadcast guidance also recommends
partitioning externally delivered system broadcasts from non-exported internal command receivers.

## Implementation

1. Keep `WeatherWidgetProvider` non-exported with only app-widget and locale lifecycle handling.
2. Keep `WidgetActionReceiver` non-exported for every internal command action.
3. Add a dedicated exported `PackageReplacedReceiver` whose only accepted action is
   `MY_PACKAGE_REPLACED` and whose only capability is rendering all existing widgets from cache.
4. Use `BroadcastAsyncRunner`/`goAsync()` so Room reads and rendering do not block the receiver.
5. Remove `MY_PACKAGE_REPLACED` handling from `WeatherWidgetProvider` to avoid two lifecycle owners.
6. Add sparse lifecycle logging for receipt and ignore unexpected actions defensively.

## Verification

1. Manifest contract: provider and command receiver remain non-exported; the package receiver is
   exported, has only the protected package-replacement action, and the provider retains widget
   metadata/update discovery.
2. Receiver behavior: the correct system action invokes one cache repaint; any other action does
   nothing.
3. Focused JVM/Robolectric tests pass with the required duration categories.
4. Debug APK assembles successfully and `git diff --check` passes.
5. Samsung runtime sequence:
   - capture current widget state and a pre-install screenshot;
   - install the APK as a replacement without manually launching the app;
   - observe `PackageReplacedReceiver`, a complete-body full push, and One UI host application;
   - verify `dumpsys appwidget` contains backed views;
   - capture a final screenshot showing the complete graph at the preserved source/view/offset/zoom.

## Verification Results

1. The focused manifest, receiver, and fresh-process rendering tests passed (6 tests):
   `WidgetManifestContractTest`, `PackageReplacedReceiverTest`, and
   `WidgetRendererDailyUiOnlyRepaintTest`.
2. `:app:ktlintCheck`, `:app:assembleDebug`, and the complete
   `:app:testByDurationDebugUnitTest` lane passed. The duration lane completed in 47 seconds with
   all Short, Medium, Long, and Localization buckets successful.
3. A pre-replacement screenshot on physical display 0 showed widget 345 as the complete hourly
   graph. The APK was then installed with `adb install -r` while One UI Home remained foreground;
   the app was not launched and no widget control was touched.
4. At 22:00:18 One UI reproduced the failure precursor by applying `remoteViews: null` to widget
   IDs 345 and 352. The app process then started automatically, `APPWIDGET_UPDATE` produced full
   cache paints for 345, 349, and 352, and `PackageReplacedReceiver` logged its protected broadcast
   receipt at 22:00:21.483 and ran the cache rebind path.
5. One UI accepted non-null `RemoteViews` for the affected widget IDs. The stable post-install
   screenshot showed the complete hourly graph, and `dumpsys appwidget` reported backed views for
   345, 349, and 352.
6. Persisted state remained unchanged: 345/352 stayed `TEMPERATURE`, 349 stayed `DAILY`, all three
   stayed on NWS with WIDE zoom and zero hourly offset, date offsets 345/349 stayed at -1, and the
   single-day epoch for 345 stayed 20621. No restoration was needed.
7. The same debug APK was then installed a second time, so the manifest and provider metadata were
   unchanged. The steady-state replacement again started the app automatically, produced full
   provider paints for all three widgets, delivered `PackageReplacedReceiver` at 22:05:16.205, and
   completed cache renders for 345, 349, and 352. A stable screenshot again showed the complete
   graph; all three `dumpsys appwidget` records retained non-null views and the saved state was still
   unchanged.

## Scope Boundaries

No desktop behavior, data-fetch cadence, widget navigation state, renderer geometry, or WorkManager
enqueue policy changes are included. Existing unrelated untracked plans remain untouched.
