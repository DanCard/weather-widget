# Desktop screenshot capture method

Another Claude Code session in a different project directory reported it couldn't take
desktop screenshots, while this project's sessions have. Diagnosed why: it's environmental
(X11 + ImageMagick present here), not project-specific.

## What works here

- `echo $XDG_SESSION_TYPE` → `x11`, `echo $DISPLAY` → `:0` — an active local X11 session,
  not headless/Wayland.
- ImageMagick's `import`/`convert` CLI tools installed (`/usr/bin/import`, `/usr/bin/convert`,
  version 7.1.2-25).
- Workflow (see `session-logs/260610-hourly-graph-zoom-pan-and-line-tweaks.md` and
  `session-logs/260615-shared-value-label-engine-and-desktop-graph-parity.md`):
  1. `xwininfo` to get a window ID, or use `-window root` for the whole screen.
  2. `import -window <id> screenshot.png` (or `import -window root ...` for 4K display,
     then crop the popup region).
  3. Convert/crop to jpg before reading — same reasoning as the Android ADB workflow
     documented in `CLAUDE.md`: raw capture output needs post-processing to be a clean,
     readable image.

This is a separate pipeline from the Android widget screenshot method (`adb exec-out
screencap` → convert to jpg, documented in `CLAUDE.md`) — two different platforms, two
different capture mechanisms, unified only by the "always convert to jpg before reading"
practice.

## Checklist for a session where desktop screenshots fail

1. `echo $DISPLAY` / `echo $XDG_SESSION_TYPE` — if `$DISPLAY` is empty, there's no X server
   to talk to (common in a container or SSH session without X forwarding). `import` fails
   outright in that case.
2. `which import convert` — if missing, install ImageMagick (`sudo apt install imagemagick`
   or equivalent).
3. If `$XDG_SESSION_TYPE` is `wayland`, `import` (X11-only) won't work against a Wayland
   compositor. Use `grim`/`slurp` (Sway/wlroots) or the DE's own tool (`gnome-screenshot`,
   `spectacle`) instead.
4. If the box is genuinely headless (no desktop session at all — e.g. a cloud dev box or CI
   container), there's no display to screenshot regardless of tooling — a hard blocker, not
   a config fix.
