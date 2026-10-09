# Desktop History of Forecasts: back arrow and gear, header rules shared with Android

User, 2026-10-09: the desktop "History of Forecasts" window was missing the back arrow and the
gear; "Can we share code from android?"

## What can be shared

Android's screen is Views/XML, desktop's is Compose, so the widgets cannot be shared. The header's
rules can, and had drifted:

| | Android (before) | Desktop (before) |
|---|---|---|
| Date label | built in `updateTitle()` | built separately in `Header()` |
| ‹ limit | private `MAX_HISTORY_DAYS_BACK = 395` copy | shared constant |
| › limit | none | today + 7 |

## Change

- `:shared` `ForecastHistoryHeader`: `dateLabel` ("Fri, Oct 9") and `canGoBack`; both platforms use
  it; Android's private constant removed. Forward paging has no limit on either platform (Android's
  rule; desktop's +7 dropped).
- Desktop header now `[←] [‹] date [›] … [source] [⟳] [⚙]`, Android's order, with icon chevrons
  instead of "◀ ▶" text. ← closes the window (Android's back finishes the screen); ⚙ opens the
  Settings window through `onOpenSettings`, wired like the popup's gear.

## Tests

`ForecastHistoryHeaderTest` (label, back limit, future days). Desktop rebuilt and restarted; user
confirmed the window looks right.
