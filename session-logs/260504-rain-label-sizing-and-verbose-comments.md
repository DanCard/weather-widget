# Session Log: Verbose-Comment `createScaledRainPaint()` + Rain Label Sizing Investigation
**Date:** May 4, 2026
**Topic:** Started by adding verbose explanatory comments to `createScaledRainPaint()` in `DailyForecastRainLabelRenderer.kt`. Branched into a meta-discussion about the project's commenting policy, then a live visual investigation of column rain labels rendering too large on the emulator. Concluded with an architectural analysis of three "rain text size" values and identification of a coupling bug in `getPrecipScaleFactor`.

---

## 1. Initial Task — Verbose-comment `createScaledRainPaint()`
**User Prompt:** *"Verbose comment comment the code at createScaledRainPaint()"*

### Plan-mode workflow
- Spawned an Explore subagent to locate the function and gather context.
- Found at `app/src/main/java/com/weatherwidget/widget/DailyForecastRainLabelRenderer.kt:273-303`.
- Three call sites: `drawDailyRainLabel()` line 57, `resolveNightHorizontalFit()` lines 251 and 262.
- Wrote plan to `/home/dcar/.claude/plans/verbose-comment-comment-the-snug-sprout.md` covering: KDoc header with the four-factor formula, per-parameter docs, expanded inline comments with worked numeric examples, and an explicit acknowledgment that this overrides the project's "minimal comments" default because the user explicitly requested it.
- Plan approved via ExitPlanMode.

### Implementation
- Added a 50-line KDoc header above the function: explained the formula `finalTextSize = baseTextSize × probScale × distanceScale × extraScale × nightScale`, each factor's role, the call sites, the unused-but-intentional `density` parameter, and the purity guarantee.
- Restructured the inline comments into four labeled sections (`Confidence`, `Temporal-distance`, `Day-vs-night`, `Compose`).
- Included a worked-example block showing `1 − 0.6 × 0.2 × (2/7) ≈ 0.97` for 80% prob @ 2 days.
- Zero behavioral change — every line of executable code is byte-identical.

### Verification
- `./gradlew assembleDebug` succeeded in 12s. KDoc syntax valid, no compilation errors.

---

## 2. Meta-Discussion — Why does the project's "minimal comments" default exist?
**User Prompt:** *"Why project's 'minimal comments' default?"*

### Three failure modes the rule guards against

1. **The drift problem.** Comments rot; code doesn't. The compiler/tests force you to update code, but nothing forces you to update a comment. A comment that was true on the day it was written silently drifts into being misleading. Readers who trust comments over code get misled; readers who learn to distrust comments wonder why they exist at all. Fewer comments = fewer drift surfaces.
2. **The "what vs. why" trap.** Code already expresses *what* it does. Comments like `// add 1 to counter` are noise. What code can't express is *why* — hidden constraints, historical bugs, surprising business rules. Default-no-comments forces every comment to justify itself by carrying information the code can't.
3. **The PR-description leak.** New code often comes with comments like `// added for ticket ABC-123` or `// used by the new onboarding flow`. Those belong in commit messages and PRs, not in the code, because the code's job is to describe its current behavior, not its history. Six months later the ticket is closed and the comment is just clutter.

### When comments earn their exceptions
- A *non-obvious why*: "we sleep 250ms here because the firmware needs that gap before the next write."
- A *load-bearing invariant* the type system can't express: "callers must hold the lock before invoking this."
- A *math derivation* where the formula isn't self-explanatory — which is exactly why `createScaledRainPaint()` was a legitimate exception. Four-factor multiplicative models with magic constants like `0.6f` and `1.5f` carry design intent that cannot be recovered from the code.

### Why explicit user request overrides the default
The rule is a *default* for unsupervised work, not a hard constraint. When a human asks for verbose comments, they're applying judgment that this is a rent-paying case and accepting the maintenance burden of keeping the comments in sync.

---

## 3. Documentation Trace — Is the comment policy written down?
**User Prompt:** *"Is project minimal comment's policy documented somewhere?"*

### Investigation
- Listed all top-level `*.md` files: `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `README.md`, `LICENSE.md`, `findings.md`, `progress.md`.
- Searched all of them for "comment", "docstring", "kdoc": **zero matches**.
- Checked `arch/ARCHITECTURE.md` (the only `ARCHITECTURE.md` in the tree): also zero matches.
- Checked `.claude/`: only contains `settings.local.json` (permissions/config), no instructions.

### Finding
**The policy is not documented in the project anywhere.** It comes from Claude Code's built-in system prompt, specifically the "Doing tasks" section:

> *Default to writing no comments. Only add one when the WHY is non-obvious: a hidden constraint, a subtle invariant, a workaround for a specific bug, behavior that would surprise a reader. If removing the comment wouldn't confuse a future reader, don't write it.*

It's a tool-level default, not a repo-level one. Same default fires whether Claude Code is working in this Android widget project or any other codebase.

### How to override per-project
- Add a "Code Style" section to `CLAUDE.md` — that file is auto-loaded into every Claude Code session for this project.
- Or promote it to `README.md` if human contributors should see it too.
- `AGENTS.md` and `GEMINI.md` exist but Claude Code does not auto-load them.

---

## 4. Visual Issue Reported on Emulator
**User Prompt:** *"On the emulator on top of today it says 38% rain chance. Too big of a font size. next day 15% rain chance font size looks like a good size. Thoughts?"*

### Investigation
- Pulled screenshot from `emulator-5554` (had to specify `-s` because both an emulator and a physical Pixel 7 Pro were attached).
- Read `HeaderPrecipCalculator.getPrecipScaleFactor()`:

```kotlin
fun getPrecipScaleFactor(precipProb: Int): Float = when {
    precipProb <= 1 -> 0.4f
    precipProb <= 2 -> 0.5f
    precipProb <= 4 -> 0.6f
    precipProb <= 8 -> 0.7f
    precipProb <= 15 -> 0.8f
    precipProb <= 25 -> 0.9f
    else -> 1.0f
}
```

### Diagnosis
The function is a **step function with a hard cliff at 25%**: anything from 26% to 100% gets the same maximum scale (`1.0f`). 38% and 80% render identically large.

| Day | Prob | probScale | distanceScale | Total scale |
|-----|------|-----------|---------------|-------------|
| Today | 38% | **1.0** (above-25 cliff) | 0.92 | **0.92** |
| Tomorrow | 15% | **0.8** | 0.89 | **0.71** |

Today's label renders ~1.29× taller than tomorrow's. The `effectiveDays = max(daysFromToday, 1.5f)` floor in `createScaledRainPaint` flattens today (day 0) and tomorrow (day 1) into the same distance bucket, so distance contributes almost nothing to differentiating them. Almost all the size delta comes from `probScale`'s cliff.

### Recommendation
Soften the curve at `HeaderPrecipCalculator.kt:39-47`. Specifically: lower the `<=25` bucket and add an intermediate `<=50` bucket so the transition isn't a cliff.

---

## 5. User Pushed for Lower Maximum
**User Prompt:** *"I still think >50% is too big. Maybe lower the max font size?"*

### Numbers laid out

| Approach | What changes | Today 38% | Tomorrow 15% |
|---|---|---|---|
| Current | — | 22dp | 17dp |
| Lower cap to 0.85 | `else -> 0.85f` | 19dp | 17dp |
| Lower cap to 0.77 | `else -> 0.77f` | ~17dp | 17dp (no hierarchy) |

Recommended a one-line change: `else -> 1.0f` → `else -> 0.85f`. Preserves low-prob gradient, keeps tomorrow at 17dp, today drops to ~19dp, hierarchy survives.

Flagged tradeoff: `getPrecipScaleFactor` is also used by the **header** precip text (`HeaderPrecipCalculator.kt:50`), so the change drags the header along.

Offered four options:
1. One-line `else -> 0.85f` (header shrinks too).
2. Same plus intermediate `<=50 -> 0.85f` bucket (smoother transition at 25%).
3. Split into separate column-only curve so header stays untouched.
4. Provide a target ratio and back-solve.

---

## 6. User Selected Option 2
**User Prompt:** *"2"*

### Bug caught during implementation
Re-reading my own option 2 description, the proposed curve was **non-monotonic**: `0.9 → 0.85 → 0.88` going from `<=25` through new `<=50` to `else`. A 26% rain day would render *smaller* than a 20% one — a perverse local minimum.

### Corrected implementation
Lowered the `<=25` bucket too, to keep the curve monotonically non-decreasing:

```kotlin
fun getPrecipScaleFactor(precipProb: Int): Float = when {
    precipProb <= 1 -> 0.4f
    precipProb <= 2 -> 0.5f
    precipProb <= 4 -> 0.6f
    precipProb <= 8 -> 0.7f
    precipProb <= 15 -> 0.8f
    precipProb <= 25 -> 0.82f   // was 0.9f
    precipProb <= 50 -> 0.85f   // NEW intermediate bucket
    else -> 0.88f               // was 1.0f
}
```

### Verification
- `./gradlew installDebug` succeeded in 11s, installed on all 3 attached devices (emulator, Samsung SM-F936U1, Pixel 7 Pro).
- Triggered `APPWIDGET_UPDATE` broadcast and screenshot capture — **interrupted by user before the screenshot completed.**

---

## 7. User Questioned the Approach
**User Prompt:** *"Why else 0.88f? Why not just reduce the base font text size?"*

### Honest acknowledgment
The user is mathematically correct. Multiplying every `probScale` value by 0.88 (the effect of my edit) is **identical** to multiplying the base text size by 0.88. The formula is `base × probScale × distanceScale × ...` — moving a constant factor between operands doesn't change the product.

I had anchored on "lower the cap" because the conversation was framed that way, but the cleaner factoring is to lower the base.

### Where the two approaches genuinely differ

1. **Header coupling.** `getPrecipScaleFactor` is shared between column labels and the header "38%" text. Touching the curve shrinks both. `RAIN_TEXT_SIZE_DP` is column-only — header is insulated. **Strict win for the user's suggestion.**
2. **Uniform vs. targeted shrink.** Lowering the base shrinks *all* column labels proportionally (including tomorrow's 15%). The curve change preserves the low-probability end. So the question becomes whether the user's "15% looks good" was an absolute or relative judgment.

### Numbers compared

| Approach | Today 38% | Tomorrow 15% | Header 38% |
|---|---|---|---|
| Original | 22dp | 17dp | unchanged |
| My curve change | 19.3dp | 17dp (preserved) | shrinks ~12% |
| Lower base to 21dp | 19.3dp | **14.9dp** (also shrinks) | unchanged |
| Lower base to 22dp | 20.2dp | 15.6dp | unchanged |

---

## 8. User Asked About Three Size Constants
**User Prompt:** *"Why is there 3 different text sizes, when there should be only one? paints.rainTextPaint.textSize, RAIN_TEXT_SIZE_DP, and HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP?"*

### Architectural analysis

There are actually **2 design constants and 1 derived runtime value**, not 3 independent sizes:

| Name | Where | What it is | Independent? |
|---|---|---|---|
| `RAIN_TEXT_SIZE_DP = 24f` | `DailyForecastGraphRenderer.kt:59` | Design constant in **dp** for column rain labels | ✅ Source of truth |
| `paints.rainTextPaint.textSize` | runtime `Paint` field | Above × `scaleFactor` × `labelScale` × density, in **px** | ❌ Derived from `RAIN_TEXT_SIZE_DP` |
| `HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP` | `HeaderConstants.kt` | Design constant in dp for **header strip** "38%" text | ✅ Genuinely separate |

### Why `rainTextPaint.textSize` exists as a separate "level"
Constructed at `DailyForecastGraphRenderer.kt:469`:
```kotlin
rainTextPaint = createTextPaint(COLOR_FORECAST, (RAIN_TEXT_SIZE_DP * scaleFactor * labelScale).dp(layout.density))
```

A 1×1 widget gets a smaller paint; a 5×3 gets a larger one. `createScaledRainPaint` reads `paints.rainTextPaint.textSize` instead of starting from raw `RAIN_TEXT_SIZE_DP` because it wants the already-widget-aware value. The function then layers its own probability/distance/night scaling on top. The indirection is doing real work.

### Why the two design constants are separate (defensible)
- The header strip has tight vertical budget (one tiny row at the top).
- The column labels live over a graph and need to be readable from across the room.
- They're tuned independently because their visual roles are independent. Collapsing them would couple decisions that should be separate.

### Where it actually IS smelly (legitimate critique)
The smelly coupling isn't the *base sizes* — it's `getPrecipScaleFactor`. That function is shared between the header and the columns. So when the user wanted to lower only the column rain labels, my edit dragged the header along for the ride. **That's** the design choice worth questioning, not the base constants.

Two ways to fix the coupling:
1. **Inline the curve at each call site.** ~3 lines.
2. **Split into `getColumnPrecipScale()` and `getHeaderPrecipScale()`.** ~5 lines, more explicit.

### Self-identified bug in my docstring
The KDoc I just wrote on `createScaledRainPaint` says:

> *"The output paint is a copy of `[PaintSet.rainTextPaint]` (base size `RAIN_TEXT_SIZE_DP = 24f`, color `COLOR_FORECAST = 0xFF5AC8FA`, defined in `DailyForecastGraphRenderer.kt`) with its `textSize` replaced by..."*

This is misleading — the function actually reads the **widget-scaled px value**, not the raw 24dp. Accurate phrasing would be: *"copied from `paints.rainTextPaint`, whose `textSize` is `RAIN_TEXT_SIZE_DP = 24f` after widget-aware scaling (`scaleFactor × labelScale`) and dp→px conversion."*

---

## Open Items / Recommended Next Moves

1. **Revert the edit to `getPrecipScaleFactor`** — it shouldn't have touched the header. This is the cleanup the user's questioning implies.
2. **Lower `RAIN_TEXT_SIZE_DP`** at `DailyForecastGraphRenderer.kt:59` — from `24f` to something in the 21–22f range, depending on whether the user prefers a stronger or gentler shrink.
3. **Fix the misleading docstring** on `createScaledRainPaint` to stop claiming the function reads the raw 24dp constant directly.
4. **(Optional)** Split `getPrecipScaleFactor` into separate column- and header-scoped curves if the coupling continues to cause friction. Not urgent.

## Files Modified This Session

| File | Change | Status |
|---|---|---|
| `DailyForecastRainLabelRenderer.kt:273-303` | Added KDoc + verbose inline comments to `createScaledRainPaint`. Zero behavioral change. | Shipped, builds clean. Docstring contains one inaccuracy noted above. |
| `HeaderPrecipCalculator.kt:39-47` | Reshaped the curve: `<=25 -> 0.82f`, new `<=50 -> 0.85f`, `else -> 0.88f`. | Shipped, builds and installs clean. Pending decision on whether to revert in favor of lowering `RAIN_TEXT_SIZE_DP` instead. |
