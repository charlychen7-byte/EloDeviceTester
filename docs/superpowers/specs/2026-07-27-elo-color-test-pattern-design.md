# Elo Color Test Pattern — Design

Date: 2026-07-27
Module: Display (`modules/display`)

## Goal

Add a new test item **"Elo Color Test Pattern"** as the **first** item in the
Display module's Level-2 page (`DisplayActivity`). Tapping it opens a screen that
shows all test patterns as a grid of thumbnails; tapping a thumbnail shows that
pattern fullscreen.

The patterns come from `Color Test Pattern 1.ppt` (repo root, legacy OLE2 binary
PPT), pages **2 through 21** — 20 slides total. Slide 1 is a title page and is
excluded.

## Source analysis

The deck has 21 slides. Only 7 slides carry an *embedded* PNG; the rest are
drawn as slide fills (solid colors and solid gray-level fields). Therefore the
faithful way to capture "all images from page 2 onward" is to **render each
slide page as an image**, not to extract embedded picture files.

Slide content (pages 2–21):
- Pages 2–6: pure solid color fields (red, etc.).
- Pages 7–11: gray-shade gradient patterns, each with a centered text label
  (e.g. "25 level gray Shade Pattern").
- Pages 12–21: solid gray-level fields, each with a black text label
  (e.g. "255 Gray", "127Gray (50%)").

Slides are 16:9. **The original text labels are kept** (faithful to source).

## Decisions (confirmed with user)

1. **Render range:** all 20 pages (2–21), text labels retained.
2. **Fullscreen fit:** stretch to fill the whole screen (`ImageView.ScaleType.FIT_XY`),
   no letterboxing. Acceptable because vertical stripes stay vertical and solid
   fields stay solid under scaling; full-bleed matters more than exact aspect
   for color/uniformity inspection.

## Image assets

Rendered offline (not at runtime) and committed as app assets:

- Tooling: LibreOffice `soffice --headless --convert-to pdf`, then
  `pdftoppm -png` on pages 2–21, at ~1920px wide.
- Location: `app/src/main/assets/color_patterns/pattern_01.png` …
  `pattern_20.png`, in slide order (pattern_01 = slide 2, pattern_20 = slide 21).
- Assets (not `res/drawable`) so images are loaded by index in a loop and are
  not subject to density resampling or drawable-name constraints.
- Size: solid-color slides compress to a few KB; total expected < ~2 MB.

## Components

### `EloColorPatternActivity` (thumbnail grid)

- Extends `core.BaseTestActivity` (inherits keep-screen-on, background
  `ExecutorService` + stop flag, `runAsync`/`ui`, `dp()`, `addView`).
- `title()` = "Elo Color Test Pattern".
- Renders a `GridLayout` (3 columns) of `ImageView` thumbnails inside the
  base class's `ScrollView`/`content` container. One cell per pattern.
- Thumbnails are decoded **downsampled** via `BitmapFactory.Options.inSampleSize`
  on the base executor (`runAsync`) and set on the UI thread (`ui`) — no
  blocking work on the UI thread (PRD §5.2). Asset names are enumerated via
  `AssetManager.list("color_patterns")` and sorted.
- Tapping a thumbnail starts `EloColorPatternViewerActivity` with the pattern
  index as an intent extra (`EXTRA_INDEX`).

### `EloColorPatternViewerActivity` (fullscreen viewer)

- Extends `modules.display.ImmersiveActivity` (hides system bars); manifest
  theme `@style/Theme.EloDeviceTester.Fullscreen`.
- Content view is a single `ImageView` with `scaleType = FIT_XY` on a black
  root background.
- Reads `EXTRA_INDEX`; loads the full-resolution bitmap for the current index
  from assets **off the UI thread**, then displays it. Before loading the next
  bitmap, recycles the previous one to avoid OOM (PRD §5.5 memory safety).
- **Tap** advances to the next pattern (wraps at the end). **Back** returns to
  the grid.
- A small index hint (e.g. "3 / 20") is shown initially and hidden on first tap.

## Wiring

- `DisplayActivity.buildUi()`: insert an "Elo Color Test Pattern" section title +
  info line + button as the **first** entries, before "Dead Pixel & Solid Color".
  Button launches `EloColorPatternActivity`.
- `AndroidManifest.xml`: declare both new activities. Viewer gets
  `android:theme="@style/Theme.EloDeviceTester.Fullscreen"`; grid uses the
  default theme like other Level-2 activities.

## PRD compliance

- **No blocking work on UI thread (§5.2):** all bitmap decoding happens on
  background executors; results posted back to the UI thread.
- **Memory safety (§5.5):** thumbnails downsampled; the viewer holds at most one
  full-res bitmap and recycles the previous one.
- **Dynamic hardware detection (§5.1):** display hardware is always present, so
  no greying-out is required for this item.

## Out of scope (YAGNI)

- No pass/fail tracking or result persistence.
- No zoom/swipe/pinch gestures — tap-to-advance only in the viewer.
- No runtime parsing of the PPT — images are pre-rendered and bundled.
