# Add temperature delta to the genmon panel text

## Context

The desktop app's popup **header** shows, next to the current temperature, a small colored
**delta** — `forecast.appliedDelta`, the offset between the latest *measured* observation and the
forecast curve (e.g. `72.5° +1.2`). It is rendered in orange `#FF6B35`, formatted `%+.1f`, and only
when `|delta| ≥ 0.1` (see `Main.kt:868` and `Main.kt:905-914`).

The user wants that same delta to appear on the **genmon panel text** — the big clock-sized
temperature number on the XFCE panel. That text is built live by `PanelIpcServer.update()` and
relayed to genmon by the C client (`genmon/genmon-weather.c` → `weather.sock`). Today the panel shows
only the temperature (e.g. `72.5°`); after this change it shows the temperature plus the colored
delta when one is applied.

Decisions confirmed with the user:
- **Target:** genmon panel text only (not the Dorkbox tray icon).
- **Color:** match the header exactly — `#FF6B35`.

## Approach

All work is in **`desktop/src/main/kotlin/com/weatherwidget/desktop/PanelIpcServer.kt`**. The data is
already present: `update()` receives the `ForecastResult`, and `forecast.appliedDelta` is populated
by `DesktopWeatherRepository` (`DesktopWeatherRepository.kt:99,111,193,197`). No new plumbing or DB
work is required.

### 1. Extract a pure markup builder (for testability)

Currently `update()` builds the Pango markup inline and stores it in the private `currentMarkup`
field — not unit-testable. Following the project's "pure-function extraction over mocking" strategy,
pull the markup string construction into a pure function, e.g. a `companion object` function:

```kotlin
internal fun buildPanelMarkup(
    body: String,        // e.g. "72.5°" or "--"
    color: String,       // temp color (#FFD500 live / #888888 stale)
    deltaText: String?,  // e.g. "+1.2", or null when no delta to show
    tooltip: String,
    clickCmd: String,
): String
```

`update()` computes `body`, `color`, `tooltip`, `clickCmd` exactly as today, computes `deltaText`
(below), then calls `buildPanelMarkup(...)` and stores the result in `currentMarkup`.

### 2. Compute the delta text (mirror the header)

In `update()`, mirror `Main.kt:868`:

```kotlin
val deltaText = forecast?.appliedDelta
    ?.takeIf { kotlin.math.abs(it) >= 0.1f }
    ?.let { String.format(Locale.US, "%+.1f", it) }
```

No degree symbol (matches the header, which uses `%+.1f` with no `°`).

### 3. Append a second Pango span

`<txt>` already carries one `<span>` for the temperature. Pango supports multiple spans in one run,
so append a smaller orange delta span only when `deltaText != null`:

```
<txt><span font='Sans Bold 20' foreground='$color' line_height='0.6'>$body</span>
     <span font='Sans Bold 11' foreground='#FF6B35' line_height='0.6'> $deltaText</span></txt>
```

(Single line in practice — shown wrapped here for readability. Note the leading space inside the
delta span for separation.) Keep the delta orange regardless of staleness, matching the header which
always shows it when present. Extract `#FF6B35` to a named constant (e.g. `DELTA_COLOR`) alongside
the existing color literals.

### Out of scope / note

- The **Dorkbox tray icon** (`TemperatureTrayPainter.kt`) is intentionally left unchanged.
- The **legacy Python fallback** (`genmon/genmon-weather.py`) computes the current temp by
  interpolation and does **not** know `appliedDelta` (that lives in `CurrentTemperatureResolver`'s
  stored-delta logic). The live path is `PanelIpcServer`; the Python script is only a fallback when
  the app isn't serving the socket. Porting delta computation into Python is not worth it — leave the
  Python script as-is. (Document this in the PR description so the parity gap is intentional.)

## Verification

1. **Unit test** — add a small test (new `PanelIpcServerTest.kt` under
   `desktop/src/test/kotlin/com/weatherwidget/desktop/`) asserting `buildPanelMarkup`:
   - includes the `#FF6B35` span and `+1.2` when `deltaText = "+1.2"`;
   - omits any delta span when `deltaText = null`;
   - still emits the temp span, `<tool>`, and `<txtclick>` in both cases.
   Run: `./gradlew :desktop:test --tests "com.weatherwidget.desktop.PanelIpcServerTest"`

2. **Live panel check** — rebuild + restart per CLAUDE.md:
   `scripts/build-start.sh` (rebuilds the distributable and restarts the daemon, which serves the
   socket). Then confirm the genmon panel text shows the orange delta next to the temperature when a
   measured observation diverges from the forecast (i.e. when `appliedDelta` is non-trivial). The
   header in the popup is the reference — panel delta value should match it.
   - To force a quick render without a full Gradle build after a code change, the daemon must be
     rebuilt (markup is generated in-process), so `build-start.sh` is the correct path here, not the
     no-Gradle `fast-desktop-restart.sh`.

3. **Sanity** — when `|delta| < 0.1` (or app stale/no data), the panel shows only the temperature, as
   before.
