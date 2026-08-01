# Pixel 7 Pro orientation stale-bitmap fix

## Incident evidence

On 2026-08-01, widget 86 was temporarily rendered at roughly half its normal portrait height after
Google Translate/Lens rotated the Pixel 7 Pro into landscape:

1. Android rotated portrait to landscape at 12:11:29.599.
2. A scheduled UI-only update rendered and partially pushed widget 86 at 12:11:47 with
   `sizeDp=785x167`, `cols=14`, and `rows=2`.
3. Android returned to portrait at 12:11:51.855, but no `onAppWidgetOptionsChanged` callback or
   immediate widget repaint followed.
4. The next scheduled update completed at 12:14:07 with the correct portrait dimensions,
   `sizeDp=373x310`, `cols=7`, and `rows=4`.

The graph content area was therefore approximately 151dp high in the landscape render and 294dp
high in the portrait render. The landscape bitmap remained in the portrait `fitCenter` ImageView
until the next scheduled repaint, matching the observed half-height transient.

## Implementation

1. Resolve widget dimensions from the home activity's orientation policy, rather than blindly from
   the foreground process configuration.
2. Resolve the default home through a package-visibility `<queries>` declaration. For Pixel Launcher,
   recover the display's natural orientation from the current rotation because its manifest reports
   `UNSPECIFIED` while the live activity dynamically requests `NOSENSOR`. For other explicitly
   portrait/landscape homes, use that orientation; homes that can rotate continue following the
   current device configuration.
3. Keep the existing Android min/max option mapping once host orientation is resolved:
   portrait uses `minWidth x maxHeight`, landscape uses `maxWidth x minHeight`.
4. Add host-selection fields to the existing sparse `headerState` diagnostic:
   `deviceOrientation`, `hostOrientation`, `orientationSource`, `homePackage`, and
   `homeScreenOrientation`. Add a VERBOSE logcat breadcrumb when foreground and host orientations
   differ.
5. Do not schedule work, fetch data, or cancel/replace any `WeatherWidgetWorker` as part of the fix.

### Rejected callback-only approach

The first implementation attempted an application-level orientation callback followed by a
cache-only repaint. Live Pixel validation rejected it: Android 37 froze the cached widget process,
deferred the callback, and produced no `WIDGET_ORIENTATION` event. A callback cannot repair the
bitmap promptly in the exact process state where this incident occurs, so that implementation and
its tests were removed before delivery.

## Verification

1. Unit-test fixed portrait/landscape launchers, Pixel Launcher's package-specific
   natural-orientation behavior, `NOSENSOR`, rotating launchers, and natural-orientation recovery at
   90-degree rotation.
2. Run the focused unit test and Android compilation, followed by the duration-bucket suite or the
   broadest practical app test task.
3. Install the debug build on the verified Pixel 7 Pro (`2A191FDH300PPW`).
4. Record the widget's initial source/view/date-offset/zoom state before runtime verification and
   restore it afterward if any test changes it.
5. Reproduce portrait -> Translate/Lens camera landscape, force a cache/UI repaint while landscape,
   then return to portrait.
6. Confirm the landscape-time `headerState` records `deviceOrientation=landscape`,
   `hostOrientation=portrait`, `orientationSource=pixel_launcher_natural`, and `sizeDp=373x310` rather
   than the incident's `785x167`.
7. Capture a final portrait screenshot and confirm the configured source/view/date-offset/zoom state
   is unchanged.

## Non-goals

- Do not change column/row sizing arithmetic or graph layout.
- Do not add a periodic worker or network fetch for orientation changes.
- Do not change launcher state, widget placement, source, view, offset, or zoom as part of the fix.
- Do not commit or push without an explicit request.

## Verification results

1. The 12 focused `WidgetSizeCalculatorColumnsTest` cases pass, including Pixel Launcher,
   `NOSENSOR`, fixed-orientation, rotating-home, and natural-rotation cases.
2. `:app:testByDurationDebugUnitTest :app:assembleDebug` passes after the final implementation.
3. On the Pixel 7 Pro, Translate camera/Lens was held in landscape and an app-owned UI-only alarm
   was delivered. Its worker logged:
   `deviceOrientation=landscape hostOrientation=portrait source=pixel_launcher_natural`
   with `selectedSizeDp=373x310`.
4. Persisted post-fix widget 86 rows at 12:46:14, 12:46:28, 12:46:30, and 12:46:32 all used
   `cols=7 rows=4 sizeDp=373x310` while the device was landscape. Pre-fix control rows used
   `cols=14 rows=2 sizeDp=785x167`.
5. The final portrait screenshot shows a full-height graph. Widget 86 remained `TEMPERATURE`, `NWS`,
   `WIDE`, date offset `-1`, and hourly offset `0`. Auto-rotate/user rotation were restored to `1/0`,
   and the device was returned to dozing.
