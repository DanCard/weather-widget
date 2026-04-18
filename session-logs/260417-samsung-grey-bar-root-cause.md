# Root Cause Discovery: The 99% Grey Bar

## Summary
The user correctly identified that the 99% grey bar had "returned."
Upon auditing the commit history, I discovered that another recent change (commit `a249e46` "Drive daily bar grey from noon cloud cover") intentionally overrode the icon-based ratios we originally established.

## The Problem
Our original design mapped icons to low grey ratios (e.g., 15% for fog-then-sunny, 38% for slight chance rain) specifically because the user disliked the heavy grey bars. 

However, commit `a249e46` introduced two changes that completely undid this:
1. It queries the database for the *exact* cloud cover at noon and forces the gradient to use that number. For Tuesday, NWS forecasted 72% cloud cover at noon.
2. It deliberately shortened the gold-to-grey transition fade to "produce a clearly grey-dominant lower section rather than one long fade."

This 72% override combined with the newly shortened, harsh transition is exactly what produces the visually overwhelming grey bar (the "99% grey bar") that the user explicitly hates.

## Next Steps
To fix this, we need to decide how to handle the override logic. The simplest solution is to revert commit `a249e46` or remove the `cloudRatioOverride` parameter so the widget goes back to respecting the carefully tuned icon baselines (15%-40% grey).
