# Show a home button on the daily view when the displayed source is not the preferred one

**Status:** ✅ Implemented 2026-09-01
**Goal:** the daily header gains a third button — home — whenever the widget/window is displaying a
source that is **not** the preferred (first-in-order) source. Tapping it puts the display source
back to the preferred one, which is the only thing that makes the button go away again.

---

## 1. Why the button can exist here at all

The daily view *is* home in the **view** sense, which is why `positionDailyIcons` deliberately
leaves `home_touch_zone` GONE and `DailyVisibilityManager.hideUnusedDailyViews` hides it. But
"home" has a second axis the daily view can be off: the **source**. Tapping the API indicator
cycles the widget's display source (`WeatherSourcePreferences.toggleDisplaySource`), and nothing
tells you how to get back except cycling all the way around. That is the state this button exits.

So the rule is not "daily view shows home"; it is **"a widget that is off-home in some axis shows
the way back"**. On daily, the only such axis is the source.

## 2. The rule

- **Visible when** `displaySource != preferredSource`, where preferred = first entry of the visible
  source order (Android `WeatherSourcePreferences.primarySource()`, desktop
  `settings.visibleSources.first()`) — **and** there is room for it.
- **Priority above the date.** The painted day-of-week/date already yields to the centre buttons on
  both platforms (Android: `centerIconsWidthDp` feeds `resolveDateDrawX`; desktop: date sits after
  the buttons in the centre cluster). Counting home in the icon width therefore makes the date step
  aside *by construction* — no new priority rule, just a wider slot.
- **Never at the cost of the existing buttons.** If the three-icon slot does not fit even INLINE,
  the home button is dropped and the observations/history pair keeps its old placement.
- **Tap = reset the display source to the preferred source.** It does NOT reset the date offset:
  panning is a separate axis, and the button's presence says nothing about it.

## 3. Android

| Change | File |
|---|---|
| `shouldShowHomeButton(currentId, visibleIds)` / `preferredSourceId(visibleIds)`, pure | `shared/.../util/PreferredSourceHome.kt` (new) |
| `ACTION_RESET_SOURCE` | `WidgetActions.kt` |
| `sourceHome(id)` request code (own base — the graph-view home keeps `BASE_HOME`) | `WidgetRequestCodes.kt` |
| `resetSource()` — set primary, refresh if the target source is stale, re-render; no-op when already primary | `WidgetIntentActionHandler.kt` |
| `handleResetSource()` under the per-widget lock | `WidgetIntentRouter.kt` |
| receiver branch | `WidgetActionReceiver.kt` |
| `setupSourceHomeShortcut()` — binds `home_icon(_inline)` + zones to the reset intent | `TemperatureTouchTargets.kt` |
| `positionDailyIcons(showHome = …)` — home zone visibility + zone width sizing | `TemperatureTouchTargets.kt` |
| `resolveDailyIconLayout()` — three-icon ladder with the two-icon fallback | `HeaderWidthChecker.kt` |
| `showHomeButton` on `HeaderState`; icon count from the layout resolver | `DailyHeaderResolver.kt`, `DailyViewHandler.kt` |
| bind the shortcut + pass `showHome` | `DailyGraphRenderer.kt` |

No layout XML change: `hourly_center_header_container` and the inline row already declare the home
zone between stations and history, so the daily row reads `[stations] [home] [history]`.

## 4. Desktop

`WidgetHeader`'s daily branch gains the same icon, in the same position, from the same shared
policy; clicking writes `weatherSource = visibleSources.first()`.

The date is dropped when the centre cluster cannot hold buttons + date: wrap that cluster in
`BoxWithConstraints`, measure the date with `rememberTextMeasurer` (the pattern
`TemperatureGraph`/`CloudCoverGraph` already use), and compare against the weighted leftover. This
is the desktop's first header fit decision, and it is deliberately the *only* one — the icons never
yield, matching Android's ladder where the date is the thing that goes.

## 5. Tests

| Test | Kind | Asserts |
|---|---|---|
| `PreferredSourceHomeTest` (shared) | unit | preferred = first visible; hidden when current == preferred, when the list is empty, and when only one source is visible |
| `DailyIconLayoutLadderTest` | unit (pure bounds, no font engine) | 3 icons CENTER when wide; INLINE mid; home dropped — pair preserved — when 3 would be HIDDEN |
| `PositionDailyIconsRoboTest` (extend) | Robolectric | home zone VISIBLE with `showHome=true` in both CENTER and INLINE, GONE otherwise (existing "daily hides home" case becomes the `showHome=false` case) |
| `DailyHeaderDateYieldsToHomeTest` | unit (`resolveHeaderDatePlacementFromBounds`) | a width that fits the date with 2 icons drops it with 3 |
| `WidgetResetSourceTest` | Robolectric | `resetSource` sets the primary source; no-op when already primary |
| `DesktopUiTest` (extend) | Compose UI | daily + non-preferred source shows `daily_home_button`; click restores the preferred source; absent when already preferred |

## 6. What changed during implementation

- **A third zone rung.** A 20dp icon in a 24dp zone leaves 4dp of air, and the pitch had only two
  rungs: airy (40dp) at ≥420dp, tight (24dp) below. A Pixel 7 Pro sits at ~412dp — one bracket
  below the cutoff — so the three buttons read as one glued control there. Added
  `DAILY_CENTER_ICON_ZONE_MEDIUM_DP = 32f` at ≥360dp: 12dp of air without spending the 16dp/zone
  the airy rung costs the date. Verified on the device.
- **The home button may take the row from CENTER to INLINE.** Falling a rung is not a reason to
  drop it — every button is still visible inline, which is what that rung is for. It is dropped
  only when three would be HIDDEN, and then the pair keeps its old placement.
- **A near-miss tap looks like the button misbehaving.** `setupDeadZoneCatchAll` binds the widget
  ROOT to `ACTION_TOGGLE_API`, so a tap a few dp outside the home zone *advances* the source — the
  exact opposite of what the button promises, and indistinguishable from a bug in it. The zone
  width is therefore asserted against the fit math for all three buttons, not just measured once.
