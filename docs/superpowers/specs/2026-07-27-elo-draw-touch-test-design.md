# Elo Draw touch test — design

**Date:** 2026-07-27
**Module:** Touch (PRD §3.7)
**Source:** Port of `EloDraw_Android_v1.0.0.apk` (`com.elotouch.elodrawandroid`, versionName 1.0.0) into EloDeviceTester.

## Goal

Add a new **Elo Draw** test as the **first** item in the Touch module menu, reproducing
all functionality of the standalone EloDraw APK — multi-touch line/point drawing, draw
modes, grid dead-zone counting, live touch-rate and coordinate readouts, screenshot
capture, and hide/show controls — adapted to run inside EloDeviceTester.

## Source app behavior (reverse-engineered from `classes.dex`)

The APK is a fullscreen drawing canvas (`MainActivity`) backed by a bitmap `Canvas`, with a
separate `SettingsActivity` and a `GridManager` helper.

**Palette** — one color per finger id (`id % 10`), exact RGB values:
`rgb(255,128,0)`, `rgb(0,204,204)`, `rgb(102,0,204)`, `RED`, `GRAY(0xFF888888)`,
`YELLOW`, `BLUE`, `GREEN`, `MAGENTA`, `BLACK`.

**Touch processing** (`processTouchEvent`, per `MotionEvent.getActionMasked()`):
- **DOWN / POINTER_DOWN:** record `Touch`; if `bDrawLine && bShowMarkers` draw a 60px crosshair
  (two lines ±30px) at the point; else if `bGridTest` (DOWN only) increment the cell's down
  counter and redraw counters; else if `bPointsOnly` draw the point. Pointer 0 updates the
  "Primary touch" text with `String.format("%04.0f, %04.0f", x, y)`.
- **MOVE:** for each pointer, update max drift (`dx`/`dy` = max abs distance from stroke-down
  point). If `bPointsOnly`: redraw background then draw each pointer as a numbered circle. Else
  if `bDrawLine`: optionally (`bShowPoints`) draw a filled circle (radius 6 if thin, 10 if
  thick) at the point, then draw a line from the pointer's previous position to the current one.
- **UP / POINTER_UP / CANCEL:** if `bPointsOnly` clear canvas; else if `bDrawLine && bShowMarkers`
  draw a stroked circle radius 30 at the release point; else if `bGridTest` (UP only) increment
  the cell's up counter and redraw. Then compute touch rate for the ending stroke:
  `rate = strokePointCount * 1000 / (now - strokeDownTimeMillis)`, shown as `"<rate> points/sec"`.
  Remove the pointer. Pointer 0 clears the "Primary touch" text.

**Point drawing** (`drawPoint`) — filled circle radius 70 in the finger color, with the finger
id drawn as white text (size 25) near the top of the circle.

**Line width** — thick = 6px (default), thin = 1px. `Paint` uses anti-alias + round join.

**Grid** (`GridManager`):
- Cell size in px = `round(xdpi / 25.4 * gridSizeMM)` (mm → px using horizontal DPI).
- Rows = `screenH / cell`, Cols = `screenW / cell`; remainder pixels distributed across the first
  few rows/cols (each may be +1px) so cells tile the full screen.
- `drawGrids()` draws light-gray (`0xFFCCCCCC`) 1px lines at alpha 150 for every row top and
  column left. Grid lines are drawn on **every** background refresh regardless of mode (faithful).
- `drawCounters()` — for any cell with downs>0 or ups>0, fill it tan (`rgb(222,184,135)`) and draw
  royal-blue (`rgb(65,105,225)`) centered text `"dw <n>"` and `"up <n>"`.

**Buttons** (`activity_main`): **Clean** (reset canvas + clear the two readouts), **Settings**
(launch settings, `startActivityForResult`), **Screenshot** (save PNG), **Exit**. A beep
(`ToneGenerator(SYSTEM, 200)`, tone 44, 150ms) plays on every button tap.

**Long-press** on the canvas (single finger, drift < 10px in both axes) toggles visibility of all
four buttons (hint text: "Long-press to hide or show all buttons").

**Settings** (`SettingsActivity`, returns extras): `DrawLine` (radio), `ThinLine` (checkbox),
`ShowPoints` (checkbox, "Show touch points on line"), `PointsOnly` (radio, "Draw touch points
only"), `GridTest` (radio, "Grid touch test"), `GridSize` (5/10/15/20 mm radio group, default 10).
Show-points and thin-line checkboxes are enabled only while "Draw line" is selected. Grid-test /
points-only being selected disables them.

**Defaults:** `bDrawLine=true, bThinLine=false, bShowMarkers=true, bShowPoints=false,
bPointsOnly=false, bGridTest=false, nGridSizeMM=10`.

### Known bug in the shipped APK (fixed in this port)

The layout contains a **"Mark touch down/up"** checkbox (`cbShowMarkers`, id `0x7f070029`,
checked by default), but `SettingsActivity` never wires it. The `ShowMarkers` extra therefore
always passes through unchanged (always `true`) — the control is dead. This port **wires it** so
the option is actually toggleable, since the requirement is that all functionality works.

## Target architecture

New files under `app/src/main/java/com/elotouch/devicetester/modules/touch/`, following codebase
conventions: Java only, programmatic UI, extend existing base activities.

### 1. `EloDrawActivity extends ImmersiveActivity`

Fullscreen host (uses the existing immersive base that hides system bars). Builds a `FrameLayout`
programmatically containing:
- an `EloDrawView` sized to fill the screen (added first, at the back);
- overlay `TextView`s: **Touch rate** (`"<n> points/sec"`), **Primary touch** (`"<x>, <y>"`), and
  the long-press hint;
- four overlay `Button`s: **Clean**, **Settings**, **Screenshot**, **Exit**.

Responsibilities:
- Hold the current `Settings` (a small plain-fields inner class or standalone class).
- Beep via `ToneGenerator` on each button tap (matching tone 44 / 150ms).
- **Clean** → `view.clean()` + clear both readout TextViews.
- **Settings** → launch `EloDrawSettingsActivity` via the AndroidX ActivityResult API
  (`registerForActivityResult(StartActivityForResult)`), passing current settings as extras; on
  OK, read extras back, apply to `Settings`, call `view.applySettings(...)` (which also rebuilds
  the grid if the mm size changed), toggle the touch-rate TextView visibility (hidden in grid
  mode, matching the APK).
- **Screenshot** → capture `view.getBitmap()`, save PNG (see adaptation below), show a result
  dialog.
- **Exit** → `finish()` (return to the Touch menu — NOT the APK's process-kill / go-home).
- Long-press on the view toggles visibility of the four buttons + readouts.
- Register the view's readout listener to update the two TextViews.

### 2. `EloDrawView extends View`

The drawing surface. Created with the real screen size; allocates an ARGB_8888 bitmap + `Canvas`.

State: the 10-color palette, `Paint` (anti-alias, round join), `SparseArray<Touch>` of active
fingers, an `EloDrawGrid`, and the current `Settings` values. `Touch` inner class mirrors the
APK's (id, action, x, y, stroke-down x/y, max dx/dy, stroke point count, stroke-down time millis).

- `onTouchEvent` — faithful reimplementation of `processTouchEvent` (all modes above). Calls
  `invalidate()` after each event; reports touch-rate and primary-touch strings to the activity
  through a listener interface.
- `onDraw` — blit the backing bitmap to the view canvas.
- `drawBackground()` — fill white, then `grid.drawGrids()`.
- `drawPoint(id, x, y, isUp)` — port of the APK's numbered-circle logic.
- `clean()` — redraw background.
- `applySettings(settings)` — update fields, set stroke width (1/6), rebuild grid on mm change.
- `getBitmap()` — return the backing bitmap for screenshot.

### 3. `EloDrawGrid`

Direct port of `GridManager`: `resetGrids(cellPx)`, `drawGrids()`, `drawCounters()`,
`incrementDownCounter(x,y)`, `incrementUpCounter(x,y)`, `getGrid(x,y)`. Backed by the same
`Canvas` the view owns. Cell px computed by the activity/view from `DisplayMetrics.xdpi` and the
mm size using `round(xdpi / 25.4 * mm)`.

### 4. `EloDrawSettingsActivity extends AppCompatActivity`

Faithful separate settings screen, built programmatically (the codebase has few layout XMLs).
Controls and their result extras (same keys as the APK):
- `DrawLine` — radio "Draw line" (default checked).
- `ThinLine` — checkbox "Draw thin line".
- `ShowPoints` — checkbox "Show touch points on line".
- `ShowMarkers` — checkbox "Mark touch down/up" (**now wired**).
- `PointsOnly` — radio "Draw touch points only".
- `GridTest` — radio "Grid touch test".
- `GridSize` — radio group 5 / 10 / 15 / 20 mm (default 10).

Enable/disable behavior: the three "Draw line" sub-checkboxes are enabled only while "Draw line"
is selected; selecting "Draw touch points only" or "Grid touch test" disables them. "About" is
kept as an info dialog (`EloDrawAndroid v1.0.0 …`), "Done" finishes. Results returned via
`setResult(RESULT_OK, intent-with-extras)`.

### 5. `TouchActivity` (edit)

Insert an **Elo Draw** section as the first block in `buildUi()` (before Multi-touch), with a
short bilingual description and a button launching `EloDrawActivity`.

### 6. `AndroidManifest.xml` (edit)

Declare `EloDrawActivity` with `@style/Theme.EloDeviceTester.Fullscreen` (like the other touch
tests) and `EloDrawSettingsActivity` (default theme).

## Adaptations from the APK (deliberate deviations)

| Aspect | APK behavior | This port |
| --- | --- | --- |
| Exit button | `finishAndRemoveTask()` + `System.exit(0)` + launch HOME | `finish()` → returns to Touch menu |
| Screenshot storage | `WRITE_EXTERNAL_STORAGE` → public `Pictures/EloDrawAndroid-Screenshots` | MediaStore → public `Pictures/EloDeviceTester` on API 29+ (no permission); lazily request `WRITE_EXTERNAL_STORAGE` on API 26–28 (PRD constraint 2) |
| Background | 1.4MB Elo logo BMP watermark | plain white (grid lines still drawn as reference) |
| Mark touch down/up | checkbox present but unwired (always on) | wired and functional |

## Data flow

`TouchActivity` → (Intent) → `EloDrawActivity` builds view + overlay → user draws → `EloDrawView`
paints into its bitmap and pushes readout strings to the activity's TextViews → **Settings** round-
trips through `EloDrawSettingsActivity` via ActivityResult extras → `EloDrawView.applySettings`
updates modes/stroke/grid → **Screenshot** reads the bitmap and writes a PNG via MediaStore.

## Error / edge handling

- Screenshot on API 26–28 with permission denied → show a "permission restricted" dialog; the rest
  of the test stays usable (PRD constraint 2).
- Screenshot IO failure → error dialog with the message; test continues.
- Grid math guards against a zero/oversized cell (clamp cell ≥ 1px) so `resetGrids` can't divide by
  zero on unusual DPIs.
- Off-UI-thread work: drawing is lightweight and event-driven (no long-running loop), so no
  executor is required; screenshot PNG compression runs on a background thread with the result
  dialog posted back to the UI thread to avoid any ANR on large displays (PRD constraint 3).

## Testing

No automated test harness exists (per CLAUDE.md). Verification:
- `./gradlew assembleDebug` builds clean.
- Manual on-device pass: each draw mode (line thick/thin, show-points, mark down/up, points-only),
  grid test at each mm size with dw/up counters, live touch-rate and primary-touch readouts, Clean,
  Screenshot save (verify file in Pictures/EloDeviceTester), long-press hide/show, Exit returns to
  the Touch menu.
