# Handoff: Huckleberry Companion — Wear OS Sleep Tracker ("Squish")

## Overview
A baby/child **sleep-tracking companion** for **Wear OS** (designed for the **Pixel Watch 3, 45mm — circular 456×456 px display**). The whole UI is a single round watch face with one big central "blob" that holds the current timer and the one primary action. The app moves through three states:

- **Awake** — shows time since the last sleep ended (counting up). Tap the blob to **Start sleep**.
- **Sleeping** — shows the live sleep duration. Tap the blob to **Pause**; **Stop** is the secondary control at the bottom.
- **Paused** — shows the frozen sleep duration. Tap the blob to **Resume**; **Stop** at the bottom.

The defining idea: **state is communicated by colour + animation, not by words.** There are no "AWAKE/ASLEEP" labels. Warm coral = awake, cool indigo = asleep, muted grey-violet = paused, and the palette **crossfades** when the state changes. A tiled, baby-themed doodle wallpaper sits behind everything (toys when awake, sheep + moons + stars at night).

## About the Design Files
The files in this bundle are **design references created in HTML/CSS/JS** — an interactive prototype showing the intended look and behavior. **They are not production code to copy directly.** The task is to **recreate these designs natively in Wear OS** using **Jetpack Compose for Wear OS (Kotlin)** and the project's established patterns. The HTML is the source of truth for layout, color, type, motion, and interaction — translate it, don't transpile it.

Open `Huckleberry Watch - Squish tweakable.html` to interact with the prototype: it shows all three states side-by-side; **tap any blob** to walk Awake → Sleeping → Paused. (The HTML also contains a "Tweaks" panel used during design exploration for trying alternate palettes/fonts/animations — that panel is a design tool, **not** an app feature. Implement only the chosen default configuration below.)

## Fidelity
**High-fidelity.** Final colors, typography, spacing, motion, and interactions. Recreate the watch face pixel-accurately on the circular display, using exact tokens below.

---

## The Chosen Default Configuration
The design explored several options; ship **this** combination:

| Aspect | Value |
|---|---|
| Palette | **Dusk → Dawn** (warm → cool → muted) |
| Font | **Comfortaa** (rounded; substitute a rounded display font if unavailable) |
| Awake idle animation | **Jiggle** |
| Asleep idle animation | **Breathing** |
| Backdrop | **Pattern** (tiled baby doodles) |
| Blob diameter | **280 px** (on the 456 px canvas ≈ 61% of width) |

---

## Screen / View (single circular screen, 3 states)

### Shared layout (identical in every state)
A vertically centered stack on a full-bleed circular background:

1. **Background** — radial gradient (per state) + tiled doodle wallpaper overlay (per state).
2. **Blob** — a 280 px, organically-rounded shape centered on screen. It is the primary tap target and contains:
   - **Timer** (large, centered).
   - **Action chip** (below the timer): a small pill with an icon + verb describing what a tap does.
3. **Stop button** — a secondary "ghost" pill pinned near the bottom of the screen (only in Sleeping & Paused; absent in Awake).

Nothing shifts position between states — only colors, the timer value, the action verb, the animation, and the presence of the Stop button change.

### State: AWAKE
- **Background gradient:** radial `at 50% 6%` from `#4a2742` → `#1c0d1e` (stop 72%).
- **Blob fill:** radial `at 32% 24%` from `#ffd9bd` → `#fb8aa3` (stop 82%).
- **Blob glow (outer shadow):** `0 24px 48px -10px rgba(251,138,163,.55)`.
- **Timer:** time since last sleep ended, format `"2h 14m"` (hours+minutes; "Xm" under 1h; "just now" under 1 min). Seed prototype value: 2h 14m.
- **Action chip:** moon icon + **"Start"**.
- **Idle animation:** Jiggle (see Motion).
- **Stop button:** not shown.

### State: SLEEPING
- **Background gradient:** `#1c1746` → `#08071f` (72%).
- **Blob fill:** `#a89dff` → `#5e4fd8` (82%).
- **Blob glow:** `0 24px 48px -10px rgba(106,91,230,.6)`.
- **Timer:** live elapsed sleep, format `"M:SS"` under 1h, `"H:MM:SS"` over (e.g. `14:36`, `1:02:09`). Counts up every second.
- **Action chip:** pause icon + **"Pause"**.
- **Idle animation:** Breathing.
- **Stop button:** shown (square icon + "Stop").

### State: PAUSED
- **Background gradient:** `#1a1830` → `#0c0b18` (72%).
- **Blob fill:** `#a39ec2` → `#565279` (84%).
- **Blob glow:** `0 24px 48px -10px rgba(86,82,121,.45)`.
- **Screen filter:** `brightness(0.84) saturate(0.72)` (whole screen dimmed).
- **Blob:** idle animation stops; blob settles to `scaleY(0.94)`.
- **Timer:** frozen sleep duration (dimmed to `rgba(255,255,255,0.72)`).
- **Action chip:** play icon + **"Resume"**.
- **Stop button:** shown.

---

## Interactions & Behavior

### Controls
- **Tap the blob** = the one contextual primary action:
  - Awake → **Start sleep**
  - Sleeping → **Pause**
  - Paused → **Resume**
- **Tap Stop** (bottom secondary) = end the sleep session → returns to Awake. Shown only in Sleeping & Paused.
- On every blob tap, play a **squish** press animation (squash-and-stretch, ~0.55s).

### State machine
```
AWAKE --tap blob (start)--> SLEEPING
SLEEPING --tap blob (pause)--> PAUSED
SLEEPING --tap Stop--> AWAKE
PAUSED --tap blob (resume)--> SLEEPING
PAUSED --tap Stop--> AWAKE
```

### Timer logic (exact)
- `lastSleepEnd`: timestamp the last sleep ended. **Awake elapsed = now − lastSleepEnd** (counts up; shown as Xh Ym).
- `sleepStart`: timestamp the current sleeping run began.
- `baseElapsed`: accumulated sleep ms from prior runs (before pauses).
- **Sleeping elapsed = baseElapsed + (now − sleepStart)**. **Paused elapsed = baseElapsed** (frozen).
- Transitions:
  - **start:** `baseElapsed = 0; sleepStart = now;` → Sleeping
  - **pause:** `baseElapsed += now − sleepStart;` → Paused
  - **resume:** `sleepStart = now;` → Sleeping
  - **stop:** `lastSleepEnd = now; baseElapsed = 0;` → Awake
- Tick the display ~ every 0.5–1s.

### Palette crossfade (important)
When the state changes, **don't hard-cut colors** — cross-fade them:
- Background gradient layers: fade over **0.8s ease**.
- Blob fill: fade over **0.7s ease**.
- Blob glow / box-shadow: **0.7s ease**.
- Screen brightness/saturation filter (for Paused): **0.6s ease**.
(The HTML achieves this by stacking the three palette layers and animating opacity; in Compose, animate the gradient colors with `animateColorAsState` or cross-fade three layers.)

---

## Motion (idle animations)
All idle blob motion is subtle and loops; respect a reduced-motion setting by falling back to the static end state.

- **Jiggle (Awake):** ~1.5s loop, ease-in-out. Rotation + slight scale: `rotate(-4°) scale(0.99)` → `rotate(3°) scale(1.03)` → `rotate(-3°) scale(1.01)` → `rotate(4°) scale(1.03)` → back.
- **Breathing (Sleeping):** ~4.6s loop, ease-in-out. `scale(0.96)` ↔ `scale(1.06)`.
- **Blob morph (subtle, under all states except Paused):** the blob's border-radius slowly cycles between organic shapes over ~7–8s, e.g. `46% 54% 52% 48% / 52% 46% 54% 48%` → `56% 44% 47% 53% / 44% 58% 42% 56%` → `48% 52% 58% 42% / 56% 48% 52% 44%`. (In Compose, animate a blob/squircle path or use a superellipse with animated parameters.)
- **Paused:** all idle motion halts; blob is static at `scaleY(0.94)`.
- **Tap squish:** ~0.55s — `scale(1,1)` → `scale(1.18,0.78)` → `scale(0.86,1.16)` → `scale(1.06,0.95)` → `scale(1,1)`.

> Other animation options exist in the prototype (Wobble/Bob/Pulse for awake; Sloshing liquid / Floating Z's for asleep). They are **not** part of the shipped default but are documented in `squish-final.css` if you want them later.

---

## Background Pattern (doodle wallpaper)
A tiled, hand-doodle line-art wallpaper sits over the gradient, **tinted white at ~0.20 opacity**, and crossfades with the state:

- **Awake tile** (`awake-pattern.svg`): suns, toy cars, planes, sailboats, beach balls, teddy bears, building blocks, stars, hearts, clouds — scattered randomly with varied size/rotation.
- **Night tile** (`night-pattern.svg`): fluffy sheep, sleepy crescent moons, stars, small dots — scattered.
- **Paused:** the night tile reads through the dimmed filter (or reuse a pause-bar tile if preferred — see `squish-final.css`).

Implementation notes for Wear OS:
- The two SVGs are **seamlessly tileable** (edge motifs wrap to the opposite side). Source viewBox is 260×260, rendered on the prototype at ~300 px tile size on the 456 px screen.
- Strokes are white `#ffffff`, ~1.3 px in the 260 viewBox (scales up with the tile). Render the whole pattern layer at **~20% opacity**.
- In Compose, either (a) import each SVG as a tiled `BitmapShader`/`drawable` clipped to the circular screen, or (b) redraw the doodles on a `Canvas`. The SVGs are included so you can convert them to vector drawables.

---

## Design Tokens

### Colors — "Dusk → Dawn" palette
| Token | Awake | Sleeping | Paused |
|---|---|---|---|
| Blob fill from → to | `#ffd9bd` → `#fb8aa3` | `#a89dff` → `#5e4fd8` | `#a39ec2` → `#565279` |
| Screen bg from → to | `#4a2742` → `#1c0d1e` | `#1c1746` → `#08071f` | `#1a1830` → `#0c0b18` |
| Glow | `rgba(251,138,163,.55)` | `rgba(106,91,230,.6)` | `rgba(86,82,121,.45)` |

- Timer text: `#ffffff` (Paused: `rgba(255,255,255,0.72)`).
- Action chip text: `rgba(255,255,255,0.94)`; chip background: `rgba(0,0,0,0.18)`.
- Stop ("ghost") text: `rgba(255,255,255,~0.8)` muted; bg `rgba(255,255,255,0.07)`; border `1.5px rgba(255,255,255,0.16)`.
- Pattern strokes: `#ffffff` at layer opacity `0.20`.

### Typography
- Family: **Comfortaa** (rounded). Fallback: any rounded geometric display font.
- **Timer:** 54 px, weight 700, `letter-spacing: -0.01em`, tabular figures, `white-space: nowrap`, `text-shadow: 0 2px 12px rgba(0,0,0,0.4)`. (54 px is on the 456 px canvas — scale proportionally to the real display, ~ 0.118 × screen width.)
- **Action chip:** 18 px, weight 600.
- **Stop button:** 22 px, weight 800.

### Sizing / spacing (on the 456 px design canvas — scale to device dp)
- Blob diameter: **280 px** (~61% of screen width).
- Vertical stack gap (timer ↔ chip): ~8–9 px.
- Action chip padding: 6 px × 16 px; corner radius 22 px; icon 16 px.
- Stop button: height 58 px, min-width 132 px, corner radius 32 px, icon ~20 px, pinned ~50 px from bottom, horizontally centered.
- Blob box-shadow (depth): `inset 0 6px 12px rgba(255,255,255,0.34), inset 0 -18px 30px rgba(0,0,0,0.26)` plus the per-state outer glow above.

### Icons (simple, monochrome white)
- Moon (Start), Pause (two bars), Play/triangle (Resume), Stop (rounded square). All ~16–20 px line/solid icons; use your icon set's equivalents.

---

## Assets
- `awake-pattern.svg`, `night-pattern.svg` — the two tileable doodle wallpapers (white line-art on transparent). Convert to vector drawables or use as tiled bitmaps. No raster assets, no external fonts beyond Comfortaa (Google Fonts).
- No third-party brand assets are used; this is an original design.

## Files in this bundle (HTML design references)
- `Huckleberry Watch - Squish tweakable.html` — the runnable prototype (open this).
- `squish-app.jsx` — top-level app + the default configuration + (design-time) Tweaks wiring.
- `squish-final.jsx` — the watch component: state machine, timer logic, blob + action chip + Stop.
- `squish-final.css` — **the authoritative spec for tokens, layout, palettes, and all animation keyframes.**
- `watch-core.jsx` — shared timer state machine, time formatters, icons.
- `concepts.css` — device frame, base screen styles, shared button styles, shared keyframes.
- `awake-pattern.svg`, `night-pattern.svg` — doodle wallpapers.
- `tweaks-panel.jsx`, `design-canvas.jsx` — design-time scaffolding only (the canvas that shows 3 states, and the Tweaks panel). **Not needed for the app**; included for completeness.

## Recommended implementation notes (Wear OS)
- Use **Jetpack Compose for Wear OS**; render inside a circular-aware scaffold. Keep all content within the round safe area.
- Model state as a sealed class / enum (`Awake`, `Sleeping`, `Paused`) plus the timestamp fields above; drive the timer with a coroutine tick.
- The blob is best done as a custom `Canvas`/`Path` (animated superellipse) with a radial `Brush` gradient + animated colors; layer the doodle pattern beneath the blob and above the background gradient.
- Persist `lastSleepEnd` / session fields so the face survives process death; consider an ongoing notification or Tile for the active session (out of scope for this visual handoff, but worth flagging to the team).
- Honor the system reduced-motion / battery-saver: drop idle loops to the static end state.
```
