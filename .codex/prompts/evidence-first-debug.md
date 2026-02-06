You are debugging a regression/bug in the Weather Widget project.

Follow this mandatory protocol:

1) Evidence gate (hard requirement)
- Do not guess.
- Do not propose or apply code changes before collecting evidence.
- If evidence cannot be collected, ask for one specific unblock item and stop.

2) Investigation order
- First: database state (source of truth).
- Second: logs/events correlation.
- Third: inference and fix proposal.

3) Database checks first
- Prefer existing repo scripts:
  - `scripts/query_captured_today.sh`
  - `scripts/query_forecasts.sh [optional_db_path]`
  - `scripts/query_forecasts_all.sh`
- If scripts do not answer the question, run direct `sqlite3` queries against:
  - `backups/<timestamp>/databases/weather_database`

4) Log checks second
- Query `app_logs` in SQLite by relevant tags.
- Use `adb logcat` only for runtime-only events that are not persisted.

5) Response format for debug tasks
- Evidence: list concrete findings from DB/logs with commands used.
- Inference: list what findings imply and confidence level.
- Action: next diagnostic command or patch plan.

Never skip directly to "likely cause" without evidence.
