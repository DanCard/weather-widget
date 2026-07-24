# Diagnose missing left half of hourly forecast line

1. Collect runtime evidence from the connected device: widget screenshot, renderer/fetch logs, widget state, and hourly forecast rows for the affected time window.
2. Trace the evidence through the hourly graph renderer and forecast stitching/query path to identify why points were absent or rejected.
3. Add sparse or verbose targeted diagnostics only if the current logs cannot distinguish the cause; implement a fix only after confirming the failing path.
4. Add or update focused tests, build/install as needed, and reproduce or verify on device before reporting completion.
