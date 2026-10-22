# A data paint after a placeholder full push goes full

## Problem (emulator, 2026-10-22)

Widget 6 painted NO_LOCATION (full push, 11:58:35). The user then set a location; every DAILY paint
after it went partial — `push=partial fullThisProcess=true` at 11:59:04, 11:59:29, 12:01:10 — and the
header row was missing. `WidgetPushDispatcher` only promoted a partial when no full push had happened
in the process (or after an idle gap); the NO_LOCATION full counted as backing. But a placeholder full
(NO_LOCATION, LOADING, ERROR, FETCHING_LOCATION) leaves a tree with the graph `GONE` and the header
chrome hidden (`applyPlaceholderChrome`), and a data handler's partial only sets what it changes from
a freshly inflated layout — so those views stayed hidden.

## Fix

The dispatcher remembers, per widget, whether its last full push came from a placeholder caller.
A complete-body partial from any other caller over such a tree is promoted to full
(`promoted=placeholder_tree`); the full push clears the mark. Header-only partials are never promoted
(unchanged: promoting one would blank the body). `recordDirectFull` marks the same way.

## Tests

- `LauncherCacheDropRecoveryTest`: NO_LOCATION full hiding `api_touch_zone`, then a DAILY partial →
  the header view is visible again (fails on the old code: stays GONE); the next DAILY partial stays
  partial.
- `WidgetPushDispatcherTest`: the pure decision — complete body over a placeholder promoted,
  header-only not.
