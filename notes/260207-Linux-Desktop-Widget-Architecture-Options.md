# Linux Desktop Widget Architecture Options

## The Fundamental Challenge

Linux doesn't have a unified widget system like Android or iOS. Each desktop environment (XFCE, GNOME, KDE) has its own incompatible widget API — and some (like XFCE) barely have one at all. There is no single "Linux widget" to target.

## Does XFCE Support Widgets?

**Not really.** XFCE has **panel plugins** (items in the taskbar), but no desktop widget surface like KDE Plasmoids or Android home screen widgets.

| Approach | What It Is | Language | Feels Native? |
|----------|-----------|----------|---------------|
| **XFCE Panel Plugin** | Item in the taskbar panel | C / Vala | Yes, but very constrained space |
| **Conky** | Draws directly on the desktop wallpaper | Lua + Cairo | Yes — this is what most XFCE users use for "widgets" |

---

## The Three Realistic Architectures

### Option 1: Conky (Best for XFCE, Desktop-Agnostic on X11)

```
┌──────────────────────────────────┐
│  Kotlin/JVM backend service      │  ← Shared KMP module
│  • Fetches weather data (Ktor)   │
│  • Stores in SQLDelight          │
│  • Writes JSON to ~/.cache/      │
└──────────┬───────────────────────┘
           │ reads JSON file
┌──────────▼───────────────────────┐
│  Conky                           │
│  • Lua + Cairo rendering         │
│  • Draws temp bars on desktop    │
│  • Transparent, no window chrome │
│  • Refreshes on timer            │
└──────────────────────────────────┘
```

**Pros:**
- Conky is actively maintained (v1.22.2, July 2025), battle-tested, lightweight
- Cairo gives sophisticated rendering — temperature bars, graphs, glass aesthetic
- Feels completely native on XFCE (and most X11 desktops)
- KMP shared module handles all the logic; Conky is just a display layer
- Already widely used for weather widgets on Linux

**Cons:**
- Lua/Cairo for rendering (not Kotlin — can't share rendering code)
- X11 only — Conky's Cairo rendering doesn't work on Wayland
- Two-process architecture (JVM backend + Conky frontend)

---

### Option 2: Compose Desktop (Pure Kotlin, DE-Agnostic)

```
┌──────────────────────────────────┐
│  Compose Desktop App             │
│  • Shared KMP module for data    │
│  • Transparent, undecorated,     │
│    always-on-top window          │
│  • Kotlin rendering (Skia)       │
│  • Looks like a floating widget  │
└──────────────────────────────────┘
```

**Pros:**
- 100% Kotlin — rendering code could share logic with Android
- JetBrains Compose Multiplatform is production-ready (v1.10, Jan 2026)
- Works on any DE (XFCE, GNOME, KDE, i3, etc.)
- Works on both X11 and Wayland
- Closest to "write once" for the desktop platform

**Cons:**
- Not a true desktop widget — it's a floating application window
- Appears in task switchers / window lists (can be mitigated with window type hints)
- JVM memory footprint (~50-100MB vs Conky's ~10MB)
- Linux transparent windows have had issues (improving with JDK 22+)

#### What Is Compose Desktop?

Compose Desktop is a **regular desktop window application framework** — built using the same UI framework as modern Android apps (Jetpack Compose), just targeting desktop JVM instead of Android.

| | Android | Desktop |
|---|---|---|
| **Framework** | Jetpack Compose | Compose Multiplatform (Desktop) |
| **Language** | Kotlin | Kotlin |
| **Rendering** | Skia on Android | Skia on JVM |
| **Output** | Android app | Regular desktop window app (JAR/native) |
| **Distribution** | APK/Play Store | `.deb`, `.rpm`, AppImage, or just a JAR |

#### Basic window app

```kotlin
fun main() = application {
    Window(title = "Weather Widget") {
        Column {
            Text("72°F", fontSize = 30.sp)
            TemperatureBar(high = 78, low = 62)
        }
    }
}
```

This gives a **regular window** — title bar, minimize/maximize/close buttons, taskbar entry. Just like any GTK or Qt app.

#### The "widget trick" — making it look like a widget

To make it *look* like a desktop widget instead of a regular app, you strip away the window chrome:

```kotlin
fun main() = application {
    Window(
        undecorated = true,   // No title bar
        transparent = true,   // See-through background
        alwaysOnTop = true,   // Stays on top of other windows
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
        ) {
            Text("72°F", color = Color.White)
        }
    }
}
```

This creates a **borderless, transparent, floating rectangle** on the desktop. Visually it looks like a widget. Technically it's still a window — the OS knows it's a window.

#### Visual comparison

```
┌─ Regular Compose Desktop window ─────────────┐
│ [—][□][×]  Weather Widget                     │  ← Title bar, taskbar entry
│                                               │
│   72°F        Today     Tomorrow              │
│   ████████    ██████    ████                   │
│               62-78°    58-74°                 │
└───────────────────────────────────────────────┘

┌─ "Widget mode" (undecorated + transparent) ───┐
│                                               │
│ ╭─────────────────────────────────────╮       │  ← Translucent rounded rect
│ │  72°F      Today     Tomorrow       │       │     floating on desktop
│ │  ████████  ██████    ████           │       │
│ │            62-78°    58-74°         │       │
│ ╰─────────────────────────────────────╯       │
│                                               │
└── (transparent area — you see desktop here) ──┘
```

#### What you gain vs. lose

**It IS:**
- A regular JVM application (runs with `java -jar weather-widget.jar`)
- A window that can be made to *look like* a widget
- Full Kotlin — shares code directly with KMP shared module
- Capable of sophisticated rendering (Skia engine, same as Chrome/Android)

**It is NOT:**
- Embedded in the desktop like a KDE Plasmoid
- Drawn on the wallpaper like Conky
- Invisible to the window manager — shows up in Alt+Tab (mitigable with window type hints)
- Zero-footprint — runs a JVM (~50-100MB RAM)

**The honest tradeoff:** Compose Desktop gives maximum code sharing (same language, same UI framework, same rendering engine). But it's fundamentally "faking" a widget by styling a window. Native widget systems like Conky or KDE Plasmoids integrate with the desktop compositor at a deeper level — they don't appear in Alt+Tab, they respect "show desktop" gestures properly, and they feel like part of the wallpaper.

---

### Option 3: EWW (ElKowar's Wacky Widgets)

```
┌──────────────────────────────────┐
│  Kotlin/JVM backend service      │  ← Shared KMP module
│  • Fetches weather, writes JSON  │
└──────────┬───────────────────────┘
           │ reads via script
┌──────────▼───────────────────────┐
│  EWW Widget                      │
│  • Yuck config + CSS styling     │
│  • GTK-based rendering           │
│  • Works on X11 AND Wayland      │
│  • True widget (no window chrome)│
└──────────────────────────────────┘
```

**Pros:**
- Desktop-agnostic, works with any WM
- Works on both X11 **and** Wayland (unlike Conky)
- CSS styling is approachable
- Active Rust-based project

**Cons:**
- Custom config language (Yuck) — another thing to learn
- Less rendering sophistication than Cairo/Conky for complex graphs
- Smaller community than Conky

---

## Other Approaches Considered

### XFCE Panel Plugin
- Built with C/Vala using `libxfce4panel`
- Reliable but constrained to the panel bar — not enough space for temperature graphs
- Not worth the effort for a weather widget with graphical displays

### GNOME Shell Extension
- Written in JavaScript (GJS)
- Only works on GNOME
- Active ecosystem, production-ready

### KDE Plasmoid
- Written in QML + JavaScript
- Most sophisticated native widget system on Linux
- Only works on KDE Plasma

### JavaFX Transparent Window
- Similar concept to Compose Desktop but older framework
- More mature transparent window support on Linux
- Less code sharing with Android than Compose

### System Tray / Notification Area
- Most compatible cross-DE approach
- Very limited display area (small icon + popup)
- Not suitable for temperature bars and graphs

---

## Comparison Matrix

| Approach | Language | DE-Agnostic | X11 | Wayland | Native Feel | Rendering Sophistication | Code Sharing w/ KMP |
|----------|----------|-------------|-----|---------|-------------|-------------------------|-------------------|
| **Conky** | Lua+Cairo | Yes (X11) | Yes | No | Excellent | High (Cairo) | Data only |
| **Compose Desktop** | Kotlin | Yes | Yes | Yes | Fair | High (Skia) | Maximum |
| **EWW** | Yuck+CSS | Yes | Yes | Yes | Good | Medium | Data only |
| **XFCE Panel Plugin** | C/Vala | No (XFCE) | Yes | ? | Excellent | Low (panel space) | None |
| **KDE Plasmoid** | QML+JS | No (KDE) | Yes | Yes | Excellent | High | Data only |
| **GNOME Extension** | JavaScript | No (GNOME) | Yes | Yes | Excellent | High | Data only |

---

## Recommendation

**For maximum code sharing (recommended): Compose Desktop**
- 100% Kotlin, shared KMP module plugs in directly
- Works on any DE, both X11 and Wayland
- The "not a true widget" tradeoff is manageable

**For best native feel on XFCE/X11: Conky**
- Draws on the desktop wallpaper, invisible to window manager
- Cairo rendering is powerful for temperature graphs
- Falls back naturally — if Compose Desktop proves unreliable, Conky is the backup
- Architecture supports both: KMP shared module stays the same either way

**The Wayland question matters for the future:** XFCE is still primarily X11, but Linux is migrating toward Wayland. Conky's Cairo rendering breaks on Wayland; Compose Desktop works on both. For longevity, Compose Desktop is the safer bet. For looking perfect *today* on X11/XFCE, Conky is hard to beat.
