# Refresh notice: four seconds, not two

User, 2026-10-15: on the Fold 4 the Observations Refresh notice was missed. Logs show both toasts
were shown (`REFRESH_TOAST … shown` 11:36:14, "Refreshed NWS" 11:36:30) for ~2.4 s each, with a
16 s silent wait between them (the user's fetch queued behind a 20 s charging-loop fetch).

## Change

- `RefreshNotice.DURATION_MS` 2 000 → 4 000 (`:shared`). The widget banner and the desktop toast
  use it exactly; it is also the one-per-window gate.
- Android Toasts cannot be 4 s — the platform has only `LENGTH_SHORT` (2 s) and `LENGTH_LONG`
  (3.5 s). `RefreshToast` and the Observations screen's "Refreshed {source}" toast use `LENGTH_LONG`.

## Tests

- `RefreshNoticeTest`: duration 4 s, gate one per 4 s.
- `RefreshToastRoboTest`: toast duration `LENGTH_LONG`; suppression window uses `DURATION_MS`.
- `RefreshNoticeStateTest` (desktop): clears at `DURATION_MS`; windows use `DURATION_MS`.

## Not done

The 16 s silent wait is the larger cause; an in-screen "Refreshing…" state that lasts until the
fetch ends is a separate change (offered, not requested).
