# Elo Color Test Pattern Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Elo Color Test Pattern" item as the first entry in the Display module that shows 20 test patterns (rendered from `Color Test Pattern 1.ppt` pages 2–21) as a thumbnail grid, each tappable to a stretched-fullscreen viewer.

**Architecture:** Patterns are pre-rendered offline to PNGs and bundled under `app/src/main/assets/color_patterns/`. A grid Activity (`EloColorPatternActivity`, extends `BaseTestActivity`) lists downsampled thumbnails; tapping one opens a fullscreen viewer Activity (`EloColorPatternViewerActivity`, extends `ImmersiveActivity`) that stretches the full-res bitmap to fill the screen and advances on tap. A small package-private helper enumerates the asset list for both.

**Tech Stack:** Java, Android (minSdk 26), programmatic UI (no layout XML), `BitmapFactory` + `AssetManager`. Assets generated with LibreOffice (`soffice`) + poppler (`pdftoppm`).

## Global Constraints

- Language: Java only — no Kotlin.
- Minimum SDK: API 26 (Android 8.0).
- UI is built programmatically in each Activity; do not add layout XML for these screens.
- No blocking work on the UI thread — all bitmap decoding runs on a background executor, results posted to the UI thread (PRD §5.2).
- Memory safety — thumbnails must be downsampled; the viewer holds at most one full-res bitmap and recycles the previous one (PRD §5.5).
- No automated test harness exists; verification is `./gradlew assembleDebug` plus install + manual check on a device/emulator.
- Package root: `com.elotouch.devicetester`; Display module package: `com.elotouch.devicetester.modules.display`.
- Fullscreen theme id: `@style/Theme.EloDeviceTester.Fullscreen`.

---

## File Structure

- Create: `app/src/main/assets/color_patterns/pattern_01.png` … `pattern_20.png` — bundled test-pattern images (slide 2 → `pattern_01`, slide 21 → `pattern_20`).
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatterns.java` — asset-name enumeration helper (shared by grid + viewer).
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternViewerActivity.java` — fullscreen stretched viewer.
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternActivity.java` — thumbnail grid.
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/display/DisplayActivity.java` — add "Elo Color Test Pattern" as first item.
- Modify: `app/src/main/AndroidManifest.xml` — declare the two new Activities.

---

## Task 1: Generate and bundle the pattern assets

**Files:**
- Create: `app/src/main/assets/color_patterns/pattern_01.png` … `pattern_20.png`

**Interfaces:**
- Consumes: nothing.
- Produces: 20 PNG files in `app/src/main/assets/color_patterns/`, named `pattern_NN.png` (zero-padded 01–20), sorted alphabetically in slide order.

This is a one-time offline asset-generation task. The tools were verified present at these paths on the dev machine:
- LibreOffice: `C:/Program Files/LibreOffice/program/soffice.exe`
- poppler pdftoppm: `C:/Users/Charly.Chen/AppData/Local/Programs/poppler/poppler-26.02.0/Library/bin/pdftoppm`

If your paths differ, substitute them (`soffice` converts PPT→PDF; `pdftoppm` rasterizes PDF pages to PNG).

- [ ] **Step 1: Render the deck to PNG pages in a temp dir**

Run (Git Bash):

```bash
SCRATCH="$(mktemp -d)"
SOFFICE="/c/Program Files/LibreOffice/program/soffice.exe"
PDFTOPPM="/c/Users/Charly.Chen/AppData/Local/Programs/poppler/poppler-26.02.0/Library/bin/pdftoppm"
cd "$SCRATCH"
"$SOFFICE" --headless --convert-to pdf "C:/Users/Charly.Chen/Desktop/ClaudeCode/EloTestApp/Color Test Pattern 1.ppt" --outdir "$SCRATCH"
"$PDFTOPPM" -png -r 192 "Color Test Pattern 1.pdf" page
ls page-*.png | wc -l
```

Expected: `21` (one PNG per slide; files `page-01.png` … `page-21.png`, each 1920×1081).

- [ ] **Step 2: Copy pages 2–21 into the assets dir as pattern_01..20**

Run:

```bash
DEST="C:/Users/Charly.Chen/Desktop/ClaudeCode/EloTestApp/EloDeviceTester/app/src/main/assets/color_patterns"
mkdir -p "$DEST"
for p in $(seq 2 21); do
  src=$(printf "page-%02d.png" "$p")
  dst=$(printf "$DEST/pattern_%02d.png" "$((p-1))")
  cp "$src" "$dst"
done
ls "$DEST"/*.png | wc -l
```

Expected: `20`. Files `pattern_01.png` (from slide 2) … `pattern_20.png` (from slide 21).

- [ ] **Step 3: Sanity-check the assets**

Run:

```bash
DEST="C:/Users/Charly.Chen/Desktop/ClaudeCode/EloTestApp/EloDeviceTester/app/src/main/assets/color_patterns"
ls "$DEST"
du -sk "$DEST"
```

Expected: 20 files named `pattern_01.png`…`pattern_20.png`; total size roughly 500–700 KB.

- [ ] **Step 4: Build to confirm assets are packaged without error**

Run (from `EloDeviceTester/`):

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/color_patterns
git commit -m "feat: bundle Elo color test pattern images (PPT pages 2-21)"
```

---

## Task 2: Asset helper + fullscreen viewer Activity

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatterns.java`
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternViewerActivity.java`
- Modify: `app/src/main/AndroidManifest.xml` (add viewer Activity with fullscreen theme)

**Interfaces:**
- Consumes: assets under `color_patterns/` from Task 1; `ImmersiveActivity` (existing base for fullscreen screens).
- Produces:
  - `EloColorPatterns.ASSET_DIR` → `String` constant `"color_patterns"`.
  - `EloColorPatterns.list(Context ctx)` → `String[]` of sorted asset file names (e.g. `pattern_01.png`), empty array on error.
  - `EloColorPatternViewerActivity.EXTRA_INDEX` → `String` intent-extra key (`int`, zero-based start index).

- [ ] **Step 1: Create the asset-enumeration helper**

Create `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatterns.java`:

```java
package com.elotouch.devicetester.modules.display;

import android.content.Context;

import java.io.IOException;
import java.util.Arrays;

/** Shared helper: enumerates the bundled color-pattern assets in slide order. */
final class EloColorPatterns {

    static final String ASSET_DIR = "color_patterns";

    private EloColorPatterns() { }

    /** Sorted asset file names (e.g. "pattern_01.png"); empty array on error. */
    static String[] list(Context ctx) {
        try {
            String[] names = ctx.getAssets().list(ASSET_DIR);
            if (names == null) return new String[0];
            Arrays.sort(names);
            return names;
        } catch (IOException e) {
            return new String[0];
        }
    }
}
```

- [ ] **Step 2: Create the fullscreen viewer Activity**

Create `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternViewerActivity.java`:

```java
package com.elotouch.devicetester.modules.display;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fullscreen viewer for the Elo color test patterns. Stretches the current
 * pattern to fill the whole screen (FIT_XY); tap advances to the next pattern
 * (wraps), Back returns to the grid. At most one full-res bitmap is held in
 * memory; the previous one is recycled on each load (PRD §5.5).
 */
public class EloColorPatternViewerActivity extends ImmersiveActivity {

    public static final String EXTRA_INDEX = "pattern_index";

    private String[] assets;
    private int index;

    private ImageView imageView;
    private TextView hint;
    private Bitmap current;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assets = EloColorPatterns.list(this);
        if (assets.length == 0) { finish(); return; }

        index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (index < 0 || index >= assets.length) index = 0;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(Color.RED);
        hint.setTextSize(14);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = 48;
        root.addView(hint, hlp);

        root.setOnClickListener(v -> {
            hint.setVisibility(View.GONE);
            index = (index + 1) % assets.length;
            load();
        });

        setContentView(root);
        load();
    }

    private void load() {
        hint.setText((index + 1) + " / " + assets.length);
        final String name = assets[index];
        io.execute(() -> {
            Bitmap bmp = null;
            try (InputStream in = getAssets().open(EloColorPatterns.ASSET_DIR + "/" + name)) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (IOException ignored) { }
            final Bitmap loaded = bmp;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (loaded != null) loaded.recycle();
                    return;
                }
                imageView.setImageBitmap(loaded);
                if (current != null && current != loaded) current.recycle();
                current = loaded;
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (imageView != null) imageView.setImageDrawable(null);
        if (current != null) { current.recycle(); current = null; }
    }
}
```

- [ ] **Step 3: Declare the viewer Activity in the manifest**

In `app/src/main/AndroidManifest.xml`, immediately after the `DisplayActivity` line (`<activity android:name=".modules.display.DisplayActivity" />`), add:

```xml
        <activity android:name=".modules.display.EloColorPatternViewerActivity"
            android:theme="@style/Theme.EloDeviceTester.Fullscreen" />
```

- [ ] **Step 4: Build**

Run (from `EloDeviceTester/`):

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (The viewer is not yet reachable from the UI; this step only confirms it compiles and the manifest is valid.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatterns.java \
        app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternViewerActivity.java \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add Elo color pattern asset helper and fullscreen viewer"
```

---

## Task 3: Thumbnail grid Activity

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternActivity.java`
- Modify: `app/src/main/AndroidManifest.xml` (add grid Activity)

**Interfaces:**
- Consumes: `EloColorPatterns.list(Context)`, `EloColorPatterns.ASSET_DIR`, and `EloColorPatternViewerActivity.EXTRA_INDEX` from Task 2; `BaseTestActivity` helpers `addInfo`, `addView`, `runAsync`, `ui`, `dp` (existing).
- Produces: `EloColorPatternActivity` — launchable Activity showing the thumbnail grid.

- [ ] **Step 1: Create the grid Activity**

Create `app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternActivity.java`:

```java
package com.elotouch.devicetester.modules.display;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.widget.GridLayout;
import android.widget.ImageView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.InputStream;

/**
 * Level-2.5 gallery for the Elo color test patterns: a scrollable grid of
 * downsampled thumbnails. Tapping a thumbnail opens the fullscreen viewer at
 * that index. Thumbnails are decoded off the UI thread (PRD §5.2).
 */
public class EloColorPatternActivity extends BaseTestActivity {

    private static final int COLUMNS = 3;

    @Override
    protected String title() {
        return "Elo Color Test Pattern";
    }

    @Override
    protected void buildUi() {
        addInfo("Tap a pattern to view fullscreen. In the viewer, tap the image to "
                + "advance, Back to return.\n"
                + "点击图案全屏显示；全屏中点击图片切换下一张，返回键退出。");

        final String[] names = EloColorPatterns.list(this);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int spacing = dp(6);
        int cell = (dm.widthPixels - dp(32) - spacing * (COLUMNS - 1)) / COLUMNS;
        int thumbH = cell * 9 / 16;

        for (int i = 0; i < names.length; i++) {
            final int index = i;
            final String name = names[i];

            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF202020);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = thumbH;
            lp.setMargins(0, 0,
                    (i % COLUMNS == COLUMNS - 1) ? 0 : spacing, spacing);
            iv.setLayoutParams(lp);

            iv.setOnClickListener(v -> {
                Intent it = new Intent(this, EloColorPatternViewerActivity.class);
                it.putExtra(EloColorPatternViewerActivity.EXTRA_INDEX, index);
                startActivity(it);
            });

            grid.addView(iv);

            runAsync(() -> {
                Bitmap thumb = decodeThumb(name, cell);
                ui(() -> { if (thumb != null) iv.setImageBitmap(thumb); });
            });
        }

        addView(grid);
    }

    /** Decode a downsampled thumbnail (~reqW px wide) from assets. */
    private Bitmap decodeThumb(String name, int reqW) {
        String path = EloColorPatterns.ASSET_DIR + "/" + name;
        try (InputStream in = getAssets().open(path)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);

            int sample = 1;
            while (reqW > 0 && bounds.outWidth / (sample * 2) >= reqW) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in2 = getAssets().open(path)) {
                return BitmapFactory.decodeStream(in2, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Declare the grid Activity in the manifest**

In `app/src/main/AndroidManifest.xml`, immediately after the `EloColorPatternViewerActivity` line added in Task 2, add (default theme, like other Level-2 pages):

```xml
        <activity android:name=".modules.display.EloColorPatternActivity" />
```

- [ ] **Step 3: Build**

Run (from `EloDeviceTester/`):

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/display/EloColorPatternActivity.java \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add Elo color pattern thumbnail grid activity"
```

---

## Task 4: Wire into the Display module as the first item

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/display/DisplayActivity.java`

**Interfaces:**
- Consumes: `EloColorPatternActivity` from Task 3; `BaseTestActivity` helpers `addSectionTitle`, `addInfo`, `addButton` (existing).
- Produces: nothing (final wiring).

- [ ] **Step 1: Add the entry as the first item in `buildUi()`**

In `DisplayActivity.java`, add these lines at the very start of `buildUi()`, before the existing `addSectionTitle("Dead Pixel & Solid Color / 坏点与纯色");` line:

```java
        addSectionTitle("Elo Color Test Pattern");
        addInfo("Elo standard color / grayscale test patterns shown as a thumbnail "
                + "gallery; tap any pattern to view fullscreen.\n"
                + "Elo 标准色彩 / 灰阶测试图集，以缩略图展示，点击任意图案全屏显示。");
        addButton("Open Elo Color Test Pattern / 打开 Elo 色彩测试图", () ->
                startActivity(new Intent(this, EloColorPatternActivity.class)));
```

Note: `android.content.Intent` is already imported in `DisplayActivity.java`; no new import is needed.

- [ ] **Step 2: Build**

Run (from `EloDeviceTester/`):

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install and manually verify the full flow**

Run (with a device/emulator connected):

```bash
./gradlew installDebug
```

Then on the device:
1. Open the app → tap the **Display 显示测试** module.
2. Confirm **Elo Color Test Pattern** is the **first** section, above "Dead Pixel & Solid Color".
3. Tap **Open Elo Color Test Pattern** → confirm a 3-column grid of 20 thumbnails appears (solid colors, gray gradients, gray-level fields).
4. Tap the first thumbnail → confirm a red field fills the **entire** screen (no black bars), with a "1 / 20" hint at the top that disappears on the next tap.
5. Tap the image repeatedly → confirm it advances through all 20 patterns and wraps back to the first.
6. Press **Back** → confirm return to the grid.

Expected: all steps pass; no crash, no ANR, patterns render fullscreen stretched.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/display/DisplayActivity.java
git commit -m "feat: add Elo Color Test Pattern as first Display module item"
```

---

## Self-Review Notes

- **Spec coverage:** render range (Task 1, pages 2–21 → 20 assets), grid/thumbnail Activity (Task 3), fullscreen stretched viewer with tap-to-advance (Task 2), first-item wiring (Task 4), off-thread decoding (Tasks 2 & 3), memory-safety recycle (Task 2). All spec sections mapped.
- **Type consistency:** `EloColorPatterns.ASSET_DIR` / `EloColorPatterns.list()` / `EloColorPatternViewerActivity.EXTRA_INDEX` are defined in Task 2 and consumed with identical names in Tasks 3–4.
- **No placeholders:** every code and command step contains the full content.
