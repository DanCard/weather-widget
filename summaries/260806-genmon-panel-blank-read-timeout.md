# genmon panel not working — blank `(genmon)` text on the XFCE panel

*2026-08-06*

User prompts:

1. "genmon panel not working"
2. "it is working and looks good"
3. "commit"

Commit: `90e33c83`

## Symptom

The XFCE panel showed the literal string `(genmon)` instead of the temperature. That string is what
genmon renders when its command produces **empty output** — so this was never a crash, a data
problem, or a dead daemon. The client was exiting with status 0 having printed nothing.

## Investigation (evidence-first, per protocol)

Reproduced before reading any source:

1. `scripts/genmon-weather.py` (per CLAUDE.md) **does not exist** — commit `581a9f70` (2026-06-03)
   moved the panel to a C/IPC design two months ago and the docs were never updated.
2. The panel's actual command lives in xfconf, not a dotfile — grepping `~/.config/xfce4/panel/`
   finds nothing. `xfconf-query -c xfce4-panel -p /plugins/plugin-16 -lv` gave
   `command = /home/dcar/projects/weather-widget/genmon/genmon-weather-bin`.
3. Running that binary directly: **exit 0, no output.** Reproduced the failure.
4. Probing `weather.sock` from Python: the server *was* serving 288 bytes of correct markup
   (`62.8° +1.7`). So the server was healthy and the client was dropping the response.
5. Timing the socket: **connect 0.0ms, first byte 313–415ms** across three runs — versus the
   client's 100ms read timeout.
6. `jstack` sampling the daemon under a hammering loop: **6/6 samples** in
   `ActualTemperatureSeriesBuilder.blendObservationSeries` ← `YesterdayDeltaCalculator.computeDelta`
   ← `DesktopWeatherRepository.resolveCurrentTempInMemory` ← `PanelIpcServer.kt:45`.

## Root cause

Two independent defects, either of which blanks the panel on its own.

1. **Client read timeout shorter than the server's response.** `genmon/genmon-weather.c:42` set
   `SO_RCVTIMEO` to 100ms while the server needed ~350ms to first byte. `read()` returned −1, the
   `while` loop at line 50 never executed, and control reached `return 0` **without printing**.
   The `fallback:` label was only reachable from a `connect()` failure, so the grey `--` placeholder
   written precisely for "app isn't serving data" was **unreachable for a merely slow server** — the
   one case it exists for.

2. **Server rendered the markup on the accept thread.** `PanelIpcServer` called `markupProvider()`
   inline in the accept loop. That provider (`DaemonProcess.kt:86-101`) runs a full multi-day IDW
   observation blend from scratch on **every panel poll**, solely to produce the small orange delta
   text. Tell that this was a known-but-unfinished design: `PanelIpcServer.kt:13` imported
   `AtomicReference` and **never used it** — the cache the class doc calls a "push/pull" design was
   started and abandoned.

The two costs were set in different languages, in different commits, and silently crossed. Neither
side logs anything: the server records a successful serve either way.

## Fixes

**`genmon/genmon-weather.c`**
- `SO_RCVTIMEO` 100ms → 2s.
- Buffer the whole response (8KB) and print once at the end. Printing per-chunk meant a mid-stream
  timeout emitted *truncated* markup, which the panel renders as garbage; buffering makes output
  all-or-nothing.
- `total == 0` now `goto fallback` instead of returning success, so a timeout shows the grey `--`
  placeholder rather than blanking the panel.

**`desktop/.../PanelIpcServer.kt`**
- Added `cachedMarkup: AtomicReference<String?>`, using the already-imported class.
- Accept path serves the cached string; only the first connect after startup renders inline.
- Re-render *after* the client is served, off its critical path. Costs no extra CPU — the panel
  polls on a fixed 120s period, so it is the same work at the same cadence, just moved.
- New `renderMarkup()` keeps the prior cached value if the provider throws, falling back to
  `unavailableMarkup()` only when nothing ever rendered — a render failure must never leave the
  panel with no markup.
- `triggerRefresh()` re-renders **before** poking the panel: the plugin event makes genmon reconnect
  almost immediately and is served whatever is cached at that instant. Refreshing afterwards would
  show the previous value and only catch up on the following poll.

**`CLAUDE.md`** — corrected the genmon section, which still described the deleted
`scripts/genmon-weather.py`. Now documents the C binary, the socket, the xfconf key, the gitignored
build, and the "never render on the accept path" constraint.

## Verification

- Client fix proven **independently**, against the still-running unfixed server: real temperature
  returned, ~350ms.
- Fallback path: `HOME=/tmp/... ./genmon-weather-bin` → grey `--` with "App not running" tooltip.
- `gcc -O3 -Wall` clean, no warnings. `:desktop:compileKotlin` clean.
- Rebuilt + restarted via `scripts/buildStart-desktop.sh`; serve latency **~350ms → 2–4ms** over six
  samples.
- Forced a real panel repaint (`xfce4-panel --plugin-event=genmon-16:refresh:bool:true`) and
  confirmed on a root screenshot: yellow `#FFD500` temp block at rows 1988–2056 and orange
  `#FF6B35` delta at 2067–2117 of the vertical left panel.
- User confirmed: "it is working and looks good".

## Follow-up (done, prompt 4: "address follow-up")

`genmon/genmon-weather-bin` is **gitignored** (`.gitignore:88`), so a fresh clone had no working
panel until `make -C genmon` was run by hand — the same class of silent breakage as the original
bug. `scripts/desktop-app-launcher-and-autostart.sh` already rebuilt the distributable when missing;
it now does the same for the panel binary.

Two deliberate choices:

- **Run `make` unconditionally, not `[ ! -x "$GENMON_BIN" ]`.** An existence check only fixes a
  *missing* binary; make's timestamp comparison also rebuilds a **stale** one after a pull that
  touched the `.c`. It is a no-op (~10ms, "Nothing to be done") when current.
- **Non-fatal.** The script runs under `set -euo pipefail`, so a bare `make` would abort the
  launcher and the *app itself* would never start on a machine without gcc — trading a degraded
  panel for no weather app at all. Wrapped in `if make ...; then ... else <warn> fi`.

Verified: `bash -n` clean; make no-op / stale-source / deleted-binary paths all correct; a simulated
`exit 127` make failure logs the warning and still reaches `exec` with script exit 0; and a real
end-to-end run from a deleted-binary state logged `panel client up to date: …` at 06:28:08, started
the app, and served the panel in 2ms.
