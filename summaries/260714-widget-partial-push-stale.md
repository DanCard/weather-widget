# Widget stuck on layout defaults after partial-only pushes (Samsung SM-F936U1)

## Symptom (2026-07-14 ~16:34)

Widget 345 (6x4, TEMPERATURE) on the Fold inner display showed:

- header `92.5° +1.5` — a value `CURR_TEMP_RESULT` dates to **16:00:06**
- body `Today` / `--°` / `--°`, mode icons hidden

`92.5` was wrong; actual current temp was ~87. `ACTION_REFRESH` (a **full** push) healed it
instantly to `87.4°` with the correct curve.

## Key finding: the body was NOT stale data

`--°` / `--°` / `Today` are the **raw XML defaults** in `res/layout/widget_weather.xml`
(`day2_high="--°"`, `day2_low="--°"`, `day2_label="@string/today"`). So the host was rendering a
freshly inflated layout with only header actions applied over it — not an old good render.

## Established facts

- Data was fine: NWS forecasts for today present at the widget site (37.417,-122.089 → 82/62).
- Renders were fine: 16:31:31 `WIDGET_PAINT widget=345 state=data push=partial`,
  `forecastCount=98`, `TODAY_BAR_DEBUG high=89.4 low=64.0 fallback=false`, computed
  `display=87.58`.
- Last **full** push: 15:56:42 (process restart at 15:56:29, `onUpdate_entry`,
  token `startup-7382643`). Every push from 15:56:42 → 16:34 was `partial`. None reached the screen.
- Boot was ~13:54; full pushes at 13:55 established the cache, and partials worked for ~2h after.
- No crashes, no tombstones, no bitmap-memory errors.

## Hypotheses eliminated (with evidence)

1. **Partial pushes are globally dropped** — refuted. Forcing the worker job at 16:40 produced 5
   `partiallyUpdateAppWidget` calls and the launcher logged `updateAppWidgetView` for 4 of them
   (the 5th, widget 349, has `host.callbacks=null` — dead host).
2. **RemoteViews bitmap-memory ceiling from unbounded action merge** — no
   `exceeds maximum bitmap memory` in any backup logcat.
3. **`AppWidgetServiceImpl: Null RemoteViews on updateAppWidgetIds`** — red herring; it follows
   **full** `updateAppWidget()` calls (and mostly for `com.stock.widget`), not our partial path.

## Open gap

Why the 16:11/16:31 partials did not land when the identical call works now. Not reproducible on
demand. `partiallyUpdateAppWidget` is documented to be **ignored until the widget has received a
full update**; the platform clears a widget's cached RemoteViews on reboot and on provider-package
update — both of which coincide with a **new app process**. Our logs record `push=partial|full` but
**not the pid**, so we cannot tell whether the failing pushes came from a process that never
established the cache with a full push.

## Planned change

### 1. `WidgetPushDispatcher` — one seam for all 9 push sites

Replaces the scattered `if (partialPush) partiallyUpdateAppWidget(...) else updateAppWidget(...)`
in TemperatureViewHandler (x3), DailyViewHandler, PrecipViewHandler, CloudCoverViewHandler.

- Process-scoped set of widget ids that have received a **full** push in this process.
- If a partial is requested for a widget with no full push in this process → **force full**.
  Rationale: a new process is exactly the boundary where the service may have dropped the cache
  (reboot / package update / widget restore), and the contract says a partial is ignored there.
  After the first full push, partials resume — so the Samsung anti-flash behaviour is preserved for
  the steady-state (all but the first push per widget per process).

### 2. Logging (deliberately low-volume — app_logs must not be swamped)

- `WIDGET_PUSH` row **only** on: forced-full, or first push per widget per process (~5 rows per
  process start). High-frequency steady-state pushes use `Log.v` only (never persisted).
- Add `pid=` to the existing `WIDGET_PAINT` message (already persisted, one paint per cycle) so
  process boundaries are recoverable from the DB alone.

## Verification

Unit-test the pure decision (`shouldForceFullPush(hasFullPushedThisProcess)`), then install and
confirm on-device: first push per process logs `forced=no_full_this_process`, steady-state stays
`push=partial`, and no visible flash on the Samsung launcher.
