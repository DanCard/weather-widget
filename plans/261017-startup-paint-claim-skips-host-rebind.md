# Startup paint claim skips a host rebind's onUpdate — widget left as "Today --°"

## User Prompt

> phone is unfolded, widget failed to display. Take a screenshot.
> … commit it, then write the plan for the skip bug

## Evidence (Fold 4 `RFCT70G352Y`, 2026-10-17, app_logs + logcat)

| Time | Event |
|---|---|
| 06:50:30 | `installDebug` |
| 06:51:05 | `onUpdate` startup batch claims 345/352/383 (`via=on_update`), paints all three (`WIDGET_RENDER_OK path=onUpdate`, 06:51:06) |
| 06:51:27 | User unfolds. Samsung launcher re-adds the widgets: `HoneySpace.WidgetItemBinder: addWidget launched. id: 345`, `perform updateAppWidget - remoteViews: null` |
| 06:51:28 | Host's `onUpdate` → `STARTUP_PAINT_SKIP widget=345 via=on_update holder=on_update` (and 352, 383) |
| after | `dumpsys appwidget`: no `views=` line for any of the app's widgets; screen shows the layout defaults `Today --°` |

Same session, the dedupe working as designed: 06:50:26, 06:50:33, 06:53:48 —
`via=package_replaced holder=on_update`, all three widgets already painted by `onUpdate`.

Healed by reinstalling (06:53:45 startup batch painted all three).

## Root cause

`StartupPaintClaims.claim` refuses **any** second claim inside `CLAIM_WINDOW_MS` (30 s), whoever
asks. It exists to drop the *other* startup trigger's duplicate (`MY_PACKAGE_REPLACED` vs
`onUpdate`; `performance/260910-post-install-cold-start-storm.md`) and documents the premise "the
second paint shows nothing the first did not".

That premise holds for the partner trigger but not for a **new host request**: a fold/unfold
re-add (and likely a launcher restart or rebind) discards the host's views and sends `onUpdate`
again. Refusing it leaves the widget unbound until some later full paint.

The claim is also never spent: after it has suppressed its partner, it would still refuse a rebind
`onUpdate` arriving later in the window (`holder=package_replaced via=on_update`).

## Fix

`StartupPaintClaims.claim` suppresses **only the partner trigger, and only once**:

1. Same `via` as the holder → granted (a repeat of the same trigger is a new host request), claim
   refreshed.
2. Different `via`, inside the window, claim not yet spent → refused (today's dedupe), claim marked
   spent.
3. Spent or expired claim → granted, claim replaced.

`Claim` gains `spent: Boolean`; the CAS loop stays. KDoc rewritten to say what the claim is for and
why a same-trigger repeat paints. `STARTUP_PAINT_SKIP` unchanged; add `STARTUP_PAINT_REPEAT
widget=… via=… holderAgeMs=…` (INFO) when rule 1 grants, so the rebind is visible in `app_logs`.

Cost: a genuine double `onUpdate` from one install would now paint twice. Not seen in the logs
above (the Fold's install pairs are `onUpdate` + `package_replaced`); the 260910 storm was the
cross-trigger pair, which stays deduped.

Not chosen: asking the host whether it still holds views — `AppWidgetManager` has no API for it.

## Tests

- `StartupPaintClaimsTest` (unit):
  - same trigger twice inside the window → both granted (the unfold case: `on_update`, `on_update`).
  - partner suppressed once, then a third claim by either trigger inside the window → granted.
  - existing cases unchanged: partner refused inside the window, expiry, unclaimed widget.
- `WidgetStartupCoordinatorTest` (integration, Robolectric): two `updateWidgets(claimVia=on_update)`
  calls 20 s apart (fake clock) → two `updateAppWidget` pushes per widget, no `STARTUP_PAINT_SKIP`;
  `package_replaced` then `on_update` → one push, one `STARTUP_PAINT_SKIP`.
- On device (Fold 4): `installDebug` folded, unfold within 30 s → `STARTUP_PAINT_REPEAT` for 345,
  `dumpsys appwidget` has `views=` for 345, screenshot shows the graph. Pixel 7 Pro install: still
  one paint per widget, partner `STARTUP_PAINT_SKIP` logged.
