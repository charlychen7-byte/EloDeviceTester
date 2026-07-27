# Elo Draw Touch Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Elo Draw" test as the first item in the Touch module, porting all functionality of `EloDraw_Android_v1.0.0.apk` (multi-touch line/point drawing, draw modes, grid dead-zone counting, live touch-rate & coordinate readouts, screenshot, hide/show controls) into EloDeviceTester.

**Architecture:** A fullscreen host `EloDrawActivity` (extends the existing `ImmersiveActivity`) builds a `FrameLayout` with an `EloDrawView` canvas plus overlay readouts and four buttons. `EloDrawView` owns a bitmap-backed `Canvas`, reproduces every touch mode, and delegates grid math to `EloDrawGrid`. A faithful `EloDrawSettingsActivity` round-trips draw options via ActivityResult extras.

**Tech Stack:** Java, Android SDK (minSdk 26), AndroidX (`appcompat`, `activity` ActivityResult API), `Canvas`/`Paint`/`Bitmap` 2D drawing, `MediaStore` for screenshots. All UI built programmatically (codebase convention — no layout XML).

## Global Constraints

- **Language: Java only** — no Kotlin.
- **Minimum SDK: API 26.**
- **UI is built programmatically** in each activity; no new layout XML files, module icons are emoji.
- **Package:** `com.elotouch.devicetester.modules.touch`.
- **Fullscreen tests** extend `com.elotouch.devicetester.modules.display.ImmersiveActivity`.
- **Lazy, per-module permissions** (PRD constraint 2): request `WRITE_EXTERNAL_STORAGE` only when saving a screenshot on API < 29, and only then; if denied, show "permission restricted" and keep the test usable.
- **No blocking work on the UI thread** (PRD constraint 3): PNG compression/save runs on a background thread; results posted back via `runOnUiThread`.
- **New Activities must be declared in `app/src/main/AndroidManifest.xml`.**
- **Verification gate for every task:** run `./gradlew assembleDebug` from `EloDeviceTester/` and confirm `BUILD SUCCESSFUL`. There is no unit-test framework in this project; per-task automated verification is a clean compile, followed by the manual device checks listed in Task 6.

---

### Task 1: `EloDrawGrid` — grid cell model + counters

Port of the APK's `GridManager`. Standalone helper with no Android-UI dependencies beyond `Canvas`/`Paint`; compiles independently.

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawGrid.java`

**Interfaces:**
- Consumes: nothing (leaf class).
- Produces:
  - `EloDrawGrid(Canvas canvas, int cellPx, int screenW, int screenH)`
  - `void setCanvas(Canvas c)`
  - `void resetGrids(int cellPx)`
  - `void drawGrids()` — draws the faint grid lines
  - `void drawCounters()` — fills touched cells and draws `dw N` / `up N`
  - `void incrementDownCounter(float x, float y)`
  - `void incrementUpCounter(float x, float y)`

- [ ] **Step 1: Create the file with full contents**

```java
package com.elotouch.devicetester.modules.touch;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.lang.reflect.Array;

/**
 * Port of EloDraw's GridManager: an mm-sized cell grid overlaid on the canvas with
 * per-cell touch-down / touch-up tap counters (used by the "Grid touch test" mode).
 */
class EloDrawGrid {

    static class Grid extends RectF {
        int col;
        int row;
        int downs;
        int ups;
    }

    private Canvas canvas;
    private Grid[][] grid;
    private final Paint paint = new Paint();
    private int cols;
    private int rows;
    private final int screenW;
    private final int screenH;

    EloDrawGrid(Canvas canvas, int cellPx, int screenW, int screenH) {
        this.canvas = canvas;
        this.screenW = screenW;
        this.screenH = screenH;
        resetGrids(cellPx);
    }

    void setCanvas(Canvas c) {
        this.canvas = c;
    }

    void resetGrids(int cell) {
        if (cell < 1) cell = 1;
        rows = Math.max(1, screenH / cell);
        cols = Math.max(1, screenW / cell);
        grid = (Grid[][]) Array.newInstance(Grid.class, rows, cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Grid();
            }
        }

        // Distribute the horizontal remainder across the first few columns (each +1px).
        int baseW = (screenW % cell) / cols + cell;
        int extraCols = screenW % baseW;
        int x = 0;
        for (int c = 0; c < cols; c++) {
            int w = c < extraCols ? baseW + 1 : baseW;
            for (int r = 0; r < rows; r++) {
                grid[r][c].row = r;
                grid[r][c].col = c;
                grid[r][c].downs = 0;
                grid[r][c].ups = 0;
                grid[r][c].left = x;
                grid[r][c].right = x + w;
            }
            x += w;
        }

        // Distribute the vertical remainder across the first few rows (each +1px).
        int baseH = cell + (screenH % cell) / rows;
        int extraRows = screenH % baseH;
        int y = 0;
        for (int r = 0; r < rows; r++) {
            int h = r < extraRows ? baseH + 1 : baseH;
            for (int c = 0; c < cols; c++) {
                grid[r][c].top = y;
                grid[r][c].bottom = y + h;
            }
            y += h;
        }
    }

    void drawGrids() {
        paint.setColor(0xFFCCCCCC);
        paint.setStrokeWidth(1f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAlpha(150);
        for (int r = 0; r < rows; r++) {
            canvas.drawLine(0, grid[r][0].top, screenW, grid[r][0].top, paint);
        }
        for (int c = 0; c < cols; c++) {
            canvas.drawLine(grid[0][c].left, 0, grid[0][c].left, screenH, paint);
        }
    }

    void drawCounters() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Grid g = grid[r][c];
                float size = g.right - g.left;
                if (g.downs > 0 || g.ups > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.rgb(222, 184, 135));
                    canvas.drawRect(g.left + 1f, g.top + 1f, g.right - 1f, g.bottom - 1f, paint);
                    paint.setColor(Color.rgb(65, 105, 225));
                    paint.setTextSize(size / 4f);
                    paint.setTextAlign(Paint.Align.CENTER);
                    float cx = g.left + size / 2f;
                    canvas.drawText("dw " + g.downs, cx, g.top + (5f * size / 12f), paint);
                    canvas.drawText("up " + g.ups, cx, g.top + (size * 7f / 10f), paint);
                }
            }
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private Grid getGrid(float x, float y) {
        for (int r = 0; r < rows; r++) {
            if (y > grid[r][0].top && y < grid[r][0].bottom) {
                for (int c = 0; c < cols; c++) {
                    if (x > grid[r][c].left && x < grid[r][c].right) {
                        return grid[r][c];
                    }
                }
            }
        }
        return null;
    }

    void incrementDownCounter(float x, float y) {
        Grid g = getGrid(x, y);
        if (g != null) g.downs++;
    }

    void incrementUpCounter(float x, float y) {
        Grid g = getGrid(x, y);
        if (g != null) g.ups++;
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run (from `EloDeviceTester/`): `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawGrid.java
git commit -m "feat(touch): add EloDrawGrid cell/counter helper for Elo Draw"
```

---

### Task 2: `EloDrawView` — the drawing surface

Custom `View` reproducing all EloDraw touch modes into a bitmap-backed canvas, using `EloDrawGrid`.

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawView.java`

**Interfaces:**
- Consumes: `EloDrawGrid(Canvas, int, int, int)`, `resetGrids(int)`, `drawGrids()`, `drawCounters()`, `incrementDownCounter(float,float)`, `incrementUpCounter(float,float)` (Task 1).
- Produces:
  - `interface Listener { void onTouchRate(String text); void onPrimaryTouch(String text); void onToggleControls(); }`
  - `EloDrawView(Context c, float xdpi)`
  - `void setListener(Listener l)`
  - `void applySettings(boolean drawLine, boolean thinLine, boolean showMarkers, boolean showPoints, boolean pointsOnly, boolean gridTest, int gridSizeMM)`
  - `void clean()`
  - `Bitmap getBitmap()` — the backing bitmap (may be null before first layout)

- [ ] **Step 1: Create the file with full contents**

```java
package com.elotouch.devicetester.modules.touch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * EloDraw drawing surface. Reproduces the APK's touch behavior: per-finger colored
 * line drawing (thin/thick), optional points-on-line, touch down/up markers,
 * points-only numbered circles, and grid down/up tap counting. Reports the live
 * touch rate and primary-touch coordinates to a Listener, and forwards long-press.
 */
@SuppressLint("ViewConstructor")
class EloDrawView extends View {

    interface Listener {
        void onTouchRate(String text);
        void onPrimaryTouch(String text);
        void onToggleControls();
    }

    // One color per finger id (id % 10), ported from EloDraw's palette.
    private static final int[] PALETTE = {
            Color.rgb(255, 128, 0), Color.rgb(0, 204, 204), Color.rgb(102, 0, 204),
            Color.RED, Color.rgb(0x88, 0x88, 0x88), Color.YELLOW,
            Color.BLUE, Color.GREEN, Color.MAGENTA, Color.BLACK
    };

    private final float xdpi;
    private final Paint paint = new Paint();
    private final SparseArray<Touch> fingers = new SparseArray<>();
    private final GestureDetector gestureDetector;
    private Listener listener;

    private Bitmap bitmap;
    private Canvas drawCanvas;
    private EloDrawGrid grid;

    // Draw-mode settings (EloDraw defaults).
    private boolean drawLine = true;
    private boolean thinLine = false;
    private boolean showMarkers = true;
    private boolean showPoints = false;
    private boolean pointsOnly = false;
    private boolean gridTest = false;
    private int gridSizeMM = 10;

    private static class Touch {
        float x;
        float y;
        float strokeDownX;
        float strokeDownY;
        float dx;
        float dy;
        long strokePointCount = 1;
        long strokeDownTime = System.currentTimeMillis();

        Touch(float x, float y) {
            this.x = x;
            this.y = y;
            this.strokeDownX = x;
            this.strokeDownY = y;
        }

        void update(float x, float y) {
            this.x = x;
            this.y = y;
            strokePointCount++;
        }
    }

    EloDrawView(Context c, float xdpi) {
        super(c);
        this.xdpi = xdpi;
        paint.setAntiAlias(true);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(6f);
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (listener != null) listener.onToggleControls();
            }
        });
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    private int cellPx() {
        return Math.max(1, Math.round(xdpi / 25.4f * gridSizeMM));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w == 0 || h == 0) return;
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        drawCanvas = new Canvas(bitmap);
        grid = new EloDrawGrid(drawCanvas, cellPx(), w, h);
        drawBackground();
        invalidate();
    }

    void applySettings(boolean drawLine, boolean thinLine, boolean showMarkers,
                       boolean showPoints, boolean pointsOnly, boolean gridTest, int gridSizeMM) {
        this.drawLine = drawLine;
        this.thinLine = thinLine;
        this.showMarkers = showMarkers;
        this.showPoints = showPoints;
        this.pointsOnly = pointsOnly;
        this.gridTest = gridTest;
        boolean gridChanged = this.gridSizeMM != gridSizeMM;
        this.gridSizeMM = gridSizeMM;
        paint.setStrokeWidth(thinLine ? 1f : 6f);
        if (grid != null && gridChanged) grid.resetGrids(cellPx());
        drawBackground();
        invalidate();
    }

    void clean() {
        drawBackground();
        invalidate();
    }

    Bitmap getBitmap() {
        return bitmap;
    }

    private void drawBackground() {
        if (drawCanvas == null) return;
        drawCanvas.drawColor(Color.WHITE);
        if (grid != null) grid.drawGrids();
    }

    private void drawPoint(int id, float x, float y) {
        paint.setColor(PALETTE[id % PALETTE.length]);
        paint.setStyle(Paint.Style.FILL);
        float r = 70f;
        drawCanvas.drawCircle(x, y, r, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(25f);
        drawCanvas.drawText(String.valueOf(id), x - 8f, y - r + 25f, paint);
    }

    private void reportPrimary(float x, float y) {
        if (listener != null) {
            listener.onPrimaryTouch(String.format(Locale.US, "%04.0f, %04.0f", x, y));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        gestureDetector.onTouchEvent(e);
        if (drawCanvas == null) return true;
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                float x = e.getX(idx);
                float y = e.getY(idx);
                if (id == 0) reportPrimary(x, y);
                paint.setColor(PALETTE[id % PALETTE.length]);
                paint.setStyle(Paint.Style.FILL);
                fingers.put(id, new Touch(x, y));
                if (drawLine) {
                    if (showMarkers) {
                        drawCanvas.drawLine(x - 30, y, x + 30, y, paint);
                        drawCanvas.drawLine(x, y - 30, x, y + 30, paint);
                    }
                } else if (gridTest && action == MotionEvent.ACTION_DOWN) {
                    grid.incrementDownCounter(x, y);
                    grid.drawCounters();
                } else if (pointsOnly) {
                    drawPoint(id, x, y);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pointsOnly) drawBackground();
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float x = e.getX(i);
                    float y = e.getY(i);
                    if (id == 0) reportPrimary(x, y);
                    Touch t = fingers.get(id);
                    if (t == null) continue;
                    float adx = Math.abs(x - t.strokeDownX);
                    if (adx > t.dx) t.dx = adx;
                    float ady = Math.abs(y - t.strokeDownY);
                    if (ady > t.dy) t.dy = ady;
                    paint.setColor(PALETTE[id % PALETTE.length]);
                    if (pointsOnly) {
                        drawPoint(id, x, y);
                    } else if (drawLine) {
                        if (showPoints) {
                            paint.setStyle(Paint.Style.FILL);
                            drawCanvas.drawCircle(x, y, thinLine ? 6f : 10f, paint);
                        }
                        drawCanvas.drawLine(t.x, t.y, x, y, paint);
                    }
                    t.update(x, y);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                float x = e.getX(idx);
                float y = e.getY(idx);
                if (id == 0 && listener != null) listener.onPrimaryTouch("");
                paint.setColor(PALETTE[id % PALETTE.length]);
                if (pointsOnly) {
                    drawBackground();
                } else if (drawLine) {
                    if (showMarkers) {
                        paint.setStyle(Paint.Style.STROKE);
                        drawCanvas.drawCircle(x, y, 30f, paint);
                    }
                } else if (gridTest && action == MotionEvent.ACTION_UP) {
                    grid.incrementUpCounter(x, y);
                    grid.drawCounters();
                }
                Touch t = fingers.get(id);
                if (t != null && listener != null) {
                    long elapsed = System.currentTimeMillis() - t.strokeDownTime;
                    long rate = elapsed > 0 ? (t.strokePointCount * 1000) / elapsed : 0;
                    listener.onTouchRate(rate + " points/sec");
                }
                fingers.remove(id);
                break;
            }
            default:
                return true;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) canvas.drawBitmap(bitmap, 0, 0, null);
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run (from `EloDeviceTester/`): `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawView.java
git commit -m "feat(touch): add EloDrawView drawing surface with all EloDraw modes"
```

---

### Task 3: `EloDrawSettingsActivity` — draw-options screen

Faithful separate settings screen, built programmatically; returns choices via result extras. Also declared in the manifest so it launches.

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawSettingsActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: launched with extras `DrawLine`/`ThinLine`/`ShowMarkers`/`ShowPoints`/`PointsOnly`/`GridTest` (boolean) and `GridSize` (int mm).
- Produces: `setResult(RESULT_OK, intent)` with the same seven extras reflecting the user's choices.

- [ ] **Step 1: Create the activity file with full contents**

```java
package com.elotouch.devicetester.modules.touch;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * EloDraw settings (faithful port of the APK's SettingsActivity), built programmatically.
 * "Draw line" / "Draw touch points only" / "Grid touch test" are mutually exclusive; the
 * thin-line, mark-down/up and show-points checkboxes apply only to "Draw line". Choices are
 * returned live via setResult extras. Note: the APK left "Mark touch down/up" unwired; this
 * port wires it so the option actually works.
 */
public class EloDrawSettingsActivity extends AppCompatActivity {

    private final Intent result = new Intent();

    private boolean drawLine;
    private boolean thinLine;
    private boolean showMarkers;
    private boolean showPoints;
    private boolean pointsOnly;
    private boolean gridTest;
    private int gridSizeMM;

    private CheckBox cbThinLine;
    private CheckBox cbShowMarkers;
    private CheckBox cbShowPoints;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Elo Draw Settings / 设置");

        Intent in = getIntent();
        drawLine = in.getBooleanExtra("DrawLine", true);
        thinLine = in.getBooleanExtra("ThinLine", false);
        showMarkers = in.getBooleanExtra("ShowMarkers", true);
        showPoints = in.getBooleanExtra("ShowPoints", false);
        pointsOnly = in.getBooleanExtra("PointsOnly", false);
        gridTest = in.getBooleanExtra("GridTest", false);
        gridSizeMM = in.getIntExtra("GridSize", 10);
        writeResult();

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header("Drawing options / 绘图选项"));

        final RadioButton rbDrawLine = new RadioButton(this);
        rbDrawLine.setText("Draw line / 画线");
        rbDrawLine.setChecked(drawLine);

        cbThinLine = new CheckBox(this);
        cbThinLine.setText("Draw thin line / 细线");
        cbThinLine.setChecked(thinLine);

        cbShowMarkers = new CheckBox(this);
        cbShowMarkers.setText("Mark touch down/up / 标记按下与抬起");
        cbShowMarkers.setChecked(showMarkers);

        cbShowPoints = new CheckBox(this);
        cbShowPoints.setText("Show touch points on line / 线上显示触点");
        cbShowPoints.setChecked(showPoints);

        final RadioButton rbPointsOnly = new RadioButton(this);
        rbPointsOnly.setText("Draw touch points only / 仅画触点");
        rbPointsOnly.setChecked(pointsOnly);

        final RadioButton rbGridTest = new RadioButton(this);
        rbGridTest.setText("Grid touch test / 网格触控测试");
        rbGridTest.setChecked(gridTest);

        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.addView(rbDrawLine);
        modeGroup.addView(indent(cbThinLine));
        modeGroup.addView(indent(cbShowMarkers));
        modeGroup.addView(indent(cbShowPoints));
        modeGroup.addView(rbPointsOnly);
        modeGroup.addView(rbGridTest);
        root.addView(modeGroup);

        updateEnabled();

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            drawLine = checkedId == rbDrawLine.getId();
            pointsOnly = checkedId == rbPointsOnly.getId();
            gridTest = checkedId == rbGridTest.getId();
            updateEnabled();
            writeResult();
        });
        cbThinLine.setOnCheckedChangeListener((b, v) -> {
            thinLine = v;
            writeResult();
        });
        cbShowMarkers.setOnCheckedChangeListener((b, v) -> {
            showMarkers = v;
            writeResult();
        });
        cbShowPoints.setOnCheckedChangeListener((b, v) -> {
            showPoints = v;
            writeResult();
        });

        root.addView(header("Grid size / 网格尺寸"));
        RadioGroup gridGroup = new RadioGroup(this);
        final RadioButton rb5 = sizeButton("5 mm", 5);
        final RadioButton rb10 = sizeButton("10 mm", 10);
        final RadioButton rb15 = sizeButton("15 mm", 15);
        final RadioButton rb20 = sizeButton("20 mm", 20);
        gridGroup.addView(rb5);
        gridGroup.addView(rb10);
        gridGroup.addView(rb15);
        gridGroup.addView(rb20);
        gridGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rb5.getId()) gridSizeMM = 5;
            else if (checkedId == rb10.getId()) gridSizeMM = 10;
            else if (checkedId == rb15.getId()) gridSizeMM = 15;
            else if (checkedId == rb20.getId()) gridSizeMM = 20;
            writeResult();
        });
        root.addView(gridGroup);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(16), 0, 0);
        Button about = new Button(this);
        about.setText("About");
        about.setAllCaps(false);
        about.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage("EloDrawAndroid v1.0.0\nUtility for evaluating touch performance.\n\n"
                        + "Copyright© 2019 Elo Touch Solutions. All rights reserved.")
                .setPositiveButton("OK", null)
                .show());
        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setOnClickListener(v -> finish());
        buttons.addView(about);
        buttons.addView(done);
        root.addView(buttons);

        setContentView(root);
    }

    private void writeResult() {
        result.putExtra("DrawLine", drawLine);
        result.putExtra("ThinLine", thinLine);
        result.putExtra("ShowMarkers", showMarkers);
        result.putExtra("ShowPoints", showPoints);
        result.putExtra("PointsOnly", pointsOnly);
        result.putExtra("GridTest", gridTest);
        result.putExtra("GridSize", gridSizeMM);
        setResult(RESULT_OK, result);
    }

    private void updateEnabled() {
        cbThinLine.setEnabled(drawLine);
        cbShowMarkers.setEnabled(drawLine);
        cbShowPoints.setEnabled(drawLine);
    }

    private RadioButton sizeButton(String label, int mm) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setChecked(gridSizeMM == mm);
        return rb;
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18f);
        tv.setPadding(0, dp(16), 0, dp(8));
        return tv;
    }

    private View indent(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(24);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
```

- [ ] **Step 2: Declare the activity in the manifest**

In `app/src/main/AndroidManifest.xml`, find the line:

```xml
        <activity android:name=".modules.touch.TouchSamplingActivity"
            android:theme="@style/Theme.EloDeviceTester.Fullscreen" />
```

Immediately after it, add:

```xml
        <activity android:name=".modules.touch.EloDrawActivity"
            android:theme="@style/Theme.EloDeviceTester.Fullscreen" />
        <activity android:name=".modules.touch.EloDrawSettingsActivity" />
```

(Both `EloDrawActivity` — used in Task 4 — and `EloDrawSettingsActivity` are declared here in one edit. `EloDrawActivity` does not exist yet; that is fine, the manifest is validated against the merged classpath at the Task 4 build, and declaring a not-yet-created class name does not fail this task's compile.)

- [ ] **Step 3: Build to verify it compiles**

Run (from `EloDeviceTester/`): `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. (If the manifest merger objects to the not-yet-created `EloDrawActivity` class, proceed to Task 4 which creates it and re-run there; the `EloDrawSettingsActivity` class itself must compile now.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawSettingsActivity.java app/src/main/AndroidManifest.xml
git commit -m "feat(touch): add EloDraw settings screen and manifest entries"
```

---

### Task 4: `EloDrawActivity` — fullscreen host

Builds the canvas + overlay controls, owns settings state, handles the settings round-trip, screenshot save (MediaStore / legacy with lazy permission), beep, exit, and hide/show.

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawActivity.java`

**Interfaces:**
- Consumes: `EloDrawView(Context,float)`, `setListener`, `applySettings(...)`, `clean()`, `getBitmap()` (Task 2); `EloDrawView.Listener` (Task 2); `EloDrawSettingsActivity` extras (Task 3); `ImmersiveActivity` base.
- Produces: launchable Activity `com.elotouch.devicetester.modules.touch.EloDrawActivity` (target of Task 5's Intent).

- [ ] **Step 1: Create the file with full contents**

```java
package com.elotouch.devicetester.modules.touch;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.modules.display.ImmersiveActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Elo Draw host (Touch module, first test item). A fullscreen canvas (EloDrawView)
 * with overlay readouts (touch rate, primary-touch coords) and four buttons:
 * Clean, Settings, Screenshot, Exit. Long-press the canvas to hide/show the overlay.
 * Ported from EloDraw_Android_v1.0.0.apk; Exit returns to the Touch menu and screenshots
 * are saved via MediaStore (API 29+) or legacy external storage with a lazy permission.
 */
public class EloDrawActivity extends ImmersiveActivity implements EloDrawView.Listener {

    private static final String RATE_LABEL = "Touch rate 报点率: ";
    private static final String POS_LABEL = "Primary touch 主触点: ";

    private EloDrawView drawView;
    private TextView tvTouchRate;
    private TextView tvTouchPos;
    private TextView tvHint;
    private LinearLayout buttonBar;
    private boolean controlsHidden = false;

    // Settings state (mirrors EloDraw defaults).
    private boolean drawLine = true;
    private boolean thinLine = false;
    private boolean showMarkers = true;
    private boolean showPoints = false;
    private boolean pointsOnly = false;
    private boolean gridTest = false;
    private int gridSizeMM = 10;

    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<String> storagePermLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);

        FrameLayout root = new FrameLayout(this);

        drawView = new EloDrawView(this, dm.xdpi);
        drawView.setListener(this);
        root.addView(drawView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Readouts (top-left).
        LinearLayout readouts = new LinearLayout(this);
        readouts.setOrientation(LinearLayout.VERTICAL);
        tvTouchRate = readout(RATE_LABEL);
        tvTouchPos = readout(POS_LABEL);
        readouts.addView(tvTouchRate);
        readouts.addView(tvTouchPos);
        FrameLayout.LayoutParams roLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        roLp.gravity = Gravity.TOP | Gravity.START;
        roLp.leftMargin = 24;
        roLp.topMargin = 24;
        root.addView(readouts, roLp);

        // Buttons (top-right).
        buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.VERTICAL);
        buttonBar.addView(makeButton("Clean 清除", () -> {
            drawView.clean();
            tvTouchRate.setText(RATE_LABEL);
            tvTouchPos.setText(POS_LABEL);
        }));
        buttonBar.addView(makeButton("Settings 设置", this::openSettings));
        buttonBar.addView(makeButton("Screenshot 截图", this::takeScreenshot));
        buttonBar.addView(makeButton("Exit 退出", this::finish));
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.TOP | Gravity.END;
        btnLp.rightMargin = 24;
        btnLp.topMargin = 24;
        root.addView(buttonBar, btnLp);

        // Hint (bottom-center).
        tvHint = new TextView(this);
        tvHint.setText("Long-press to hide or show all buttons / 长按隐藏或显示按钮");
        tvHint.setTextColor(Color.BLUE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hintLp.bottomMargin = 24;
        root.addView(tvHint, hintLp);

        setContentView(root);

        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                        Intent d = r.getData();
                        drawLine = d.getBooleanExtra("DrawLine", true);
                        thinLine = d.getBooleanExtra("ThinLine", false);
                        showMarkers = d.getBooleanExtra("ShowMarkers", true);
                        showPoints = d.getBooleanExtra("ShowPoints", false);
                        pointsOnly = d.getBooleanExtra("PointsOnly", false);
                        gridTest = d.getBooleanExtra("GridTest", false);
                        gridSizeMM = d.getIntExtra("GridSize", 10);
                        drawView.applySettings(drawLine, thinLine, showMarkers,
                                showPoints, pointsOnly, gridTest, gridSizeMM);
                        tvTouchRate.setVisibility(gridTest ? View.INVISIBLE : View.VISIBLE);
                    }
                });

        storagePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        saveBitmap(snapshot());
                    } else {
                        showMessage("Screenshot", "Storage permission restricted; cannot save. "
                                + "权限被拒绝，无法保存。");
                    }
                });
    }

    private void openSettings() {
        Intent i = new Intent(this, EloDrawSettingsActivity.class);
        i.putExtra("DrawLine", drawLine);
        i.putExtra("ThinLine", thinLine);
        i.putExtra("ShowMarkers", showMarkers);
        i.putExtra("ShowPoints", showPoints);
        i.putExtra("PointsOnly", pointsOnly);
        i.putExtra("GridTest", gridTest);
        i.putExtra("GridSize", gridSizeMM);
        settingsLauncher.launch(i);
    }

    private void takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        saveBitmap(snapshot());
    }

    @Nullable
    private Bitmap snapshot() {
        Bitmap src = drawView.getBitmap();
        return src == null ? null : Bitmap.createBitmap(src);
    }

    private void saveBitmap(@Nullable final Bitmap bmp) {
        if (bmp == null) {
            showMessage("Screenshot", "Nothing to capture yet. 暂无可保存内容。");
            return;
        }
        final String name = "EloDraw_"
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".png";
        new Thread(() -> {
            String msg;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/EloDeviceTester");
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IOException("MediaStore insert failed");
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        if (os == null) throw new IOException("openOutputStream failed");
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    cv.clear();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                    msg = "Saved to Pictures/EloDeviceTester/" + name;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES), "EloDeviceTester");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
                    File out = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    MediaScannerConnection.scanFile(this,
                            new String[]{out.getAbsolutePath()}, null, null);
                    msg = "Saved to " + out.getAbsolutePath();
                }
            } catch (Exception ex) {
                msg = "Screenshot failed: " + ex.getMessage();
            }
            final String finalMsg = msg;
            runOnUiThread(() -> showMessage("Screenshot", finalMsg));
        }).start();
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void beep() {
        try {
            new ToneGenerator(AudioManager.STREAM_SYSTEM, 80)
                    .startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Exception ignored) {
        }
    }

    private TextView readout(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.DKGRAY);
        tv.setTextSize(16f);
        return tv;
    }

    private Button makeButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            beep();
            action.run();
        });
        return b;
    }

    // EloDrawView.Listener
    @Override
    public void onTouchRate(String text) {
        tvTouchRate.setText(RATE_LABEL + text);
    }

    @Override
    public void onPrimaryTouch(String text) {
        tvTouchPos.setText(POS_LABEL + text);
    }

    @Override
    public void onToggleControls() {
        controlsHidden = !controlsHidden;
        int vis = controlsHidden ? View.INVISIBLE : View.VISIBLE;
        buttonBar.setVisibility(vis);
        tvTouchPos.setVisibility(vis);
        tvHint.setVisibility(vis);
        tvTouchRate.setVisibility(
                controlsHidden ? View.INVISIBLE : (gridTest ? View.INVISIBLE : View.VISIBLE));
    }
}
```

- [ ] **Step 2: Build to verify it compiles (and the Task 3 manifest entry now resolves)**

Run (from `EloDeviceTester/`): `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/touch/EloDrawActivity.java
git commit -m "feat(touch): add EloDrawActivity host with settings, screenshot, hide/show"
```

---

### Task 5: Add "Elo Draw" as the first item in the Touch menu

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/touch/TouchActivity.java`

**Interfaces:**
- Consumes: `EloDrawActivity` (Task 4); `BaseTestActivity.addSectionTitle(String)`, `addInfo(String)`, `addButton(String, Runnable)` (existing).
- Produces: nothing downstream.

- [ ] **Step 1: Add the import**

In `TouchActivity.java`, the existing imports are:

```java
import android.content.Intent;

import com.elotouch.devicetester.core.BaseTestActivity;
```

Leave them as-is (both `Intent` and `BaseTestActivity` are already imported; `EloDrawActivity` is in the same package, so no new import is needed).

- [ ] **Step 2: Insert the Elo Draw section as the first block in `buildUi()`**

Find the first line inside `buildUi()`:

```java
        addSectionTitle("Multi-touch / 多点触控");
```

Immediately **before** it, insert:

```java
        addSectionTitle("Elo Draw / 触控绘图");
        addInfo("Full EloDraw touch tool: multi-touch line/point drawing, down/up markers, "
                + "grid dead-zone counting, live touch-rate & coordinate readout, and screenshot. "
                + "Long-press hides the buttons; Back to exit.\n"
                + "EloDraw 触控绘图工具：多点画线/画点、按下与抬起标记、网格死角计数、"
                + "实时报点率与坐标显示、截图。长按隐藏按钮，返回键退出。");
        addButton("Start Elo Draw / 开始触控绘图", () ->
                startActivity(new Intent(this, EloDrawActivity.class)));
```

- [ ] **Step 3: Build to verify it compiles**

Run (from `EloDeviceTester/`): `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/touch/TouchActivity.java
git commit -m "feat(touch): add Elo Draw as the first Touch module test item"
```

---

### Task 6: Manual on-device verification

No automated tests exist. After Task 5 builds clean, install and verify on a real touch device (emulator touch is limited to a single pointer, so multi-touch checks need hardware).

**Files:** none (verification only).

- [ ] **Step 1: Install**

Run (from `EloDeviceTester/`): `./gradlew installDebug`
Expected: `BUILD SUCCESSFUL`, app installed.

- [ ] **Step 2: Verify each behavior**

Open the app → **Touch 触摸测试** → confirm **Elo Draw / 触控绘图** is the **first** item → **Start Elo Draw**. Check:
- [ ] Default mode draws a thick colored line per finger; a crosshair appears on touch-down and a hollow circle on release (Mark touch down/up on).
- [ ] "Primary touch" shows live `x, y` for finger 0 and clears on release; "Touch rate" shows `N points/sec` after a stroke.
- [ ] Multiple fingers each draw in a distinct color.
- [ ] **Settings** → toggle **Draw thin line** → lines become thin (1px). Toggle **Show touch points on line** → dots appear along lines. Toggle **Mark touch down/up** off → no crosshair/release circle (confirms the previously-dead control now works).
- [ ] **Settings** → **Draw touch points only** → fingers show as large numbered circles that follow the finger and clear on release; thin/points checkboxes are disabled.
- [ ] **Settings** → **Grid touch test** → tapping cells shows `dw N` / `up N` counts; "Touch rate" is hidden; changing **Grid size** (5/10/15/20 mm) rebuilds the grid at a visibly different cell size.
- [ ] **Clean** clears the canvas and both readouts.
- [ ] **Screenshot** saves a PNG (check **Pictures/EloDeviceTester** in a gallery/Files app); on API < 29 the storage permission is requested the first time, and denying it shows "permission restricted" without crashing.
- [ ] **Long-press** the canvas toggles all buttons/readouts hidden and shown.
- [ ] **Exit** returns to the Touch menu (does not close the whole app).

---

## Notes for the implementer

- **Run all Gradle commands from the `EloDeviceTester/` directory** (where `gradlew` lives). On Windows PowerShell use `./gradlew.bat assembleDebug`; the wrapper is committed so no Gradle install is needed.
- **Do not create layout XML** — this codebase builds UI programmatically by convention.
- The palette, stroke widths (1/6), circle radii (6/10 on-line, 30 release, 70 points-only), marker length (±30), and touch-rate formula are ported verbatim from the APK; keep them exact.
