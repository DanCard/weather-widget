# Day tap with missing hourly data → in-widget "data ends" banner (not Settings)

## Final behavior
Tapping a day whose **active source** has no hourly data no longer opens Settings. It now shows one
centered, prominent banner framed as the refresh result, and clears on its own:

> **Results of refresh:**
> No hourly forecast for Tue Jul 7 — data ends Mon Jul 6 at 4 PM

A best-effort background refresh still fires. Routing is unchanged for days that *do* have partial
data — those open the hourly graph as before; only a fully-empty active source shows the banner.

Why this framing: the refresh fetches the *whole* forecast (not the missing date), and NWS simply
doesn't publish hourly that far out — so the horizon end **is** the meaningful refresh result. That's
why it can be computed at tap and framed as "Results of refresh:".

## What the DB investigation found
NWS hourly ended **Mon Jul 6** (3–4 PM), so **Tue Jul 7 had 0 NWS hours** while Open-Meteo/Silurian
covered it. With the active source NWS, the gate correctly fell to the message branch.

## Two real bugs live testing exposed (why it "didn't clear" / "didn't show results")
1. **WorkManager `setInitialDelay` is not a reliable short timer** — the delayed clear worker fired
   late or not at all on all three devices. Replaced with a coroutine `delay` held in the broadcast's
   `goAsync` window + a prompt repaint.
2. **`shouldSkipDailyUiOnlyRepaint` skipped the daily rebuild on UI-only repaints**
   (`state=skipped_ui_only`), so the banner never painted on those updates and never cleared. Gated
   that skip on `hasTransientMessagePending`.

## Verification
- `DailyFutureDayNoHourlyClickIntegrationTest` passes (no Settings launch, stays DAILY, banner
  renders, log written); sibling routing tests pass — no regression.
- Live `ACTION_DAY_CLICK` broadcast for Tue Jul 7 on the emulator: exact message text confirmed in
  prefs, ~5–7s clear confirmed in logs; confirmed acceptable on the user's devices.
- Final `assembleDebug` + androidTest compile clean; temp debug logging removed; no dangling refs.

Build note: `compileDebugKotlin` doesn't package the APK — a stale APK masked the fix mid-session
until switching to `assembleDebug` before `adb install`.

## Open option
The banner is computed at tap, so "Results of refresh:" appears *before* the background refresh
finishes. For these far-future days that's accurate (the refresh won't change the horizon). Making it
reflect a genuinely post-refresh re-check is the async path deliberately backed out for reliability;
it can be re-added more robustly if desired.

## Files
`WeatherWidgetProvider.kt` (branch rewrite, `lastHourlyEndLabelForSource`, `formatNoHourlyDayLabel`,
coroutine-delay clear), `WidgetStateManager.kt` (transient-message state + `hasTransientMessagePending`),
`DailyViewHandler.kt` (`bindTransientMessage`), `WidgetRenderer.kt` (skip gate),
`widget_weather.xml` + `widget_message_bg.xml` + `strings.xml`,
`DailyFutureDayNoHourlyClickIntegrationTest.kt`.
