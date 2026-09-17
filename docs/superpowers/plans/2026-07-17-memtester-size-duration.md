# Memtester Size/Duration Controls + Result Styling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick the Memtester test size (1/4 total RAM vs 80% available RAM) and an optional duration (minutes, blank = infinite) on the Memtester test page, color-code the PASSED/FAILED result (green/red), and switch the elapsed-time display from `mm:ss` to `hh:mm:ss`.

**Architecture:** `NativeToolTestActivity` (shared base for `MemtesterActivity` and `QmesaActivity`) gets two new overridable hooks — `buildExtraControls()` (extra UI between the description and Start/Stop) and `testDurationMs()` (optional auto-stop duration) — plus color-coded results and `hh:mm:ss` formatting that both subclasses inherit for free. `MemtesterActivity` overrides the two new hooks to add a `RadioGroup` (size choice) and an `EditText` (duration in minutes) and changes `buildArgs()` to use the selected radio instead of `min(...)`. `QmesaActivity` is untouched.

**Tech Stack:** Java (Android, minSdk 26), no test framework in this repo — manual on-device verification.

## Global Constraints

- Language: Java only (no Kotlin).
- Auto-stop-by-duration must reuse the existing `stop()` path exactly (sets `userStopRequested = true` before calling `runner.stop()`), so it inherits the existing "killed by OS vs user stop" and PASSED/FAILED judgment unchanged — no new status branch is introduced.
- `main.postDelayed(autoStopRunnable, ...)` must be cancelled with `main.removeCallbacks(autoStopRunnable)` both at the start of a new `start()` call and inside `onFinished()`, so a stale timer from a previous run can never fire during/after a later run.
- Invalid duration input (empty, non-numeric, zero, negative) must silently fall back to "run until stopped" — no error toast, no crash.
- `core/NativeProcessRunner.java` is NOT modified by this plan.
- `QmesaActivity.java` is NOT modified by this plan — it inherits the color/`hh:mm:ss` changes automatically via the base class and must continue to compile and behave the same otherwise.
- Design reference: `docs/superpowers/specs/2026-07-17-memtester-size-duration-design.md`.

---

### Task 1: Add extension hooks + color-coded results + hh:mm:ss to `NativeToolTestActivity`

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java` (full-file replace, see below)

**Interfaces:**
- Consumes: `com.elotouch.devicetester.core.NativeProcessRunner` (unchanged), `BaseTestActivity` protected members (unchanged).
- Produces: two new `protected` members subclasses can override:
  - `protected void buildExtraControls()` — no-op by default, called in `buildUi()` right after `addInfo(description())` and before the status line is created.
  - `protected Long testDurationMs()` — returns `null` by default (infinite), called at the top of `start()`.
  - Also produces a `private static String formatElapsed(long totalSeconds)` helper used internally (not consumed by subclasses).

- [ ] **Step 1: Replace `NativeToolTestActivity.java` with the following full content**

```java
package com.elotouch.devicetester.modules.ddr;

import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.NativeProcessRunner;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Base Activity for a bundled native diagnostic tool test (memtester, QMESA):
 * renders a description, optional extra controls, a status/elapsed-time line,
 * Start/Stop buttons, and a rolling terminal-style log below them, then
 * launches the tool via {@link NativeProcessRunner} and reports a final
 * color-coded PASSED/FAILED status with elapsed time (hh:mm:ss) when stopped
 * or finished.
 */
public abstract class NativeToolTestActivity extends BaseTestActivity {

    private static final int MAX_DISPLAY_LINE_LENGTH = 200;
    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");

    /** Bundled executable name under jniLibs/arm64-v8a/, e.g. "libmemtester.so". */
    protected abstract String soName();

    /** Command-line arguments; computed fresh each time Start is pressed. */
    protected abstract String[] buildArgs();

    /** Substring marking a failure in the tool's output, e.g. "FAILURE" or "FAILED". */
    protected abstract String failureKeyword();

    /** Bilingual description shown at the top of the page. */
    protected abstract String description();

    /**
     * Extra controls (radio groups, inputs) inserted between the description
     * and the Start/Stop buttons. Default: none.
     */
    protected void buildExtraControls() {
    }

    /**
     * Optional test duration in milliseconds; null means run until manually
     * stopped (default). Read fresh each time Start is pressed.
     */
    protected Long testDurationMs() {
        return null;
    }

    private final NativeProcessRunner runner = new NativeProcessRunner();
    private final Deque<String> lastLines = new ArrayDeque<>();
    private final Object logBufferLock = new Object();
    private final List<String> pendingLines = new ArrayList<>();
    private boolean uiFlushScheduled;

    private TextView statusText;
    private TextView logText;
    private Button startButton;
    private Button stopButton;

    private boolean running;
    private volatile boolean failureSeen;
    private long startTimeMs;
    private String startFailure;
    private boolean userStopRequested;
    private int defaultStatusColor;

    private final Runnable autoStopRunnable = this::stop;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
            statusText.setText(String.format(Locale.US,
                    "%s  Elapsed 已测试时间: %s",
                    failureSeen ? "FAILURE detected 检测到失败" : "Running... 运行中",
                    formatElapsed(elapsed)));
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void buildUi() {
        addInfo(description());
        buildExtraControls();
        statusText = addInfo("Tap Start. 点击开始。");
        defaultStatusColor = statusText.getCurrentTextColor();
        startButton = addButton("Start / 开始", this::start);
        stopButton = addButton("Stop / 停止", this::stop);
        stopButton.setEnabled(false);
        logText = addInfo("");
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackgroundColor(Color.BLACK);
        logText.setTextColor(Color.WHITE);

        if (!NativeProcessRunner.isArm64Supported()) {
            startButton.setEnabled(false);
            statusText.setText("Unsupported CPU architecture (requires arm64-v8a).\n"
                    + "当前设备架构不支持 arm64-v8a 原生工具。");
        }
    }

    private void start() {
        if (running) return;
        running = true;
        failureSeen = false;
        startFailure = null;
        userStopRequested = false;
        lastLines.clear();
        synchronized (logBufferLock) {
            pendingLines.clear();
            uiFlushScheduled = false;
        }
        startTimeMs = System.currentTimeMillis();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusText.setTextColor(defaultStatusColor);
        logText.setText("");
        statusText.setText("Running... 运行中");
        main.post(tick);

        main.removeCallbacks(autoStopRunnable);
        Long durationMs = testDurationMs();
        if (durationMs != null) {
            main.postDelayed(autoStopRunnable, durationMs);
        }

        String[] args = buildArgs();
        runAsync(() -> {
            try {
                runner.run(NativeToolTestActivity.this, soName(), args, this::onLine);
            } catch (IOException e) {
                startFailure = e.getMessage();
            }
            ui(this::onFinished);
        });
    }

    private void stop() {
        userStopRequested = true;
        runner.stop();
    }

    private void onLine(String line) {
        if (line.contains(failureKeyword())) failureSeen = true;
        boolean shouldSchedule = false;
        synchronized (logBufferLock) {
            pendingLines.add(line);
            if (!uiFlushScheduled) {
                uiFlushScheduled = true;
                shouldSchedule = true;
            }
        }
        if (shouldSchedule) {
            ui(this::flushPendingLines);
        }
    }

    private void flushPendingLines() {
        List<String> toAppend;
        synchronized (logBufferLock) {
            toAppend = new ArrayList<>(pendingLines);
            pendingLines.clear();
            uiFlushScheduled = false;
        }
        for (String l : toAppend) {
            if (lastLines.size() >= 10) lastLines.removeFirst();
            lastLines.addLast(truncateForDisplay(l));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lastLines) sb.append(l).append('\n');
        logText.setText(sb.toString());
    }

    private static String truncateForDisplay(String line) {
        if (line.length() <= MAX_DISPLAY_LINE_LENGTH) return line;
        return "…" + line.substring(line.length() - MAX_DISPLAY_LINE_LENGTH);
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    private void onFinished() {
        running = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        main.removeCallbacks(autoStopRunnable);
        long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
        String elapsedStr = formatElapsed(elapsed);
        int exitCode = runner.lastExitCode();
        boolean killedBySignal = exitCode >= 129 && exitCode <= 192;
        if (startFailure != null) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText("Failed to start 启动失败: " + startFailure);
        } else if (killedBySignal && !userStopRequested) {
            int signal = exitCode - 128;
            statusText.setTextColor(defaultStatusColor);
            statusText.setText(String.format(Locale.US,
                    "Killed by OS (signal %d), elapsed %s — device sandbox policy may not "
                            + "support this binary.\n被系统终止（信号 %d），已测试时间 %s"
                            + "——设备沙箱策略可能不支持此二进制文件。",
                    signal, elapsedStr, signal, elapsedStr));
        } else if (failureSeen) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败，已测试时间 %s，请查看日志 / see log below, elapsed %s",
                    elapsedStr, elapsedStr));
        } else {
            statusText.setTextColor(PASS_COLOR);
            statusText.setText(String.format(Locale.US,
                    "PASSED 测试完成，已测试时间 %s / no errors detected, elapsed %s",
                    elapsedStr, elapsedStr));
        }
    }

    @Override
    protected void onStopTests() {
        userStopRequested = true;
        runner.stop();
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run from `EloDeviceTester/`:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (This alone confirms `QmesaActivity` — which relies only on the unchanged abstract hooks — still compiles unmodified against the new base class.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java
git commit -m "feat: add extension hooks, colored results, hh:mm:ss to NativeToolTestActivity"
```

---

### Task 2: Add size/duration controls to `MemtesterActivity`

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ddr/MemtesterActivity.java` (full-file replace, see below)

**Interfaces:**
- Consumes: `NativeToolTestActivity.buildExtraControls()` / `testDurationMs()` (from Task 1), and `BaseTestActivity.addSectionTitle(String)` / `addView(View)` (unchanged, inherited transitively).
- Produces: nothing consumed elsewhere — `MemtesterActivity` is only referenced by class literal from `DdrActivity`, which does not change.

- [ ] **Step 1: Replace `MemtesterActivity.java` with the following full content**

```java
package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/**
 * Runs the open-source memtester binary (PRD DDR §3.1 native-tool addendum)
 * against a user-selected memory size (1/4 of total RAM or 80% of available
 * RAM), for a user-selected duration in minutes (blank = run until stopped).
 */
public class MemtesterActivity extends NativeToolTestActivity {

    private RadioButton quarterTotalRadio;
    private RadioButton avail80Radio;
    private EditText durationMinutesInput;

    @Override
    protected String title() {
        return "Memtester";
    }

    @Override
    protected void buildExtraControls() {
        addSectionTitle("Test Size / 测试内存大小");
        quarterTotalRadio = new RadioButton(this);
        quarterTotalRadio.setId(View.generateViewId());
        quarterTotalRadio.setText("1/4 Total RAM / 1/4 总内存");
        avail80Radio = new RadioButton(this);
        avail80Radio.setId(View.generateViewId());
        avail80Radio.setText("80% Available RAM / 80% 可用内存");
        RadioGroup sizeGroup = new RadioGroup(this);
        sizeGroup.setOrientation(RadioGroup.VERTICAL);
        sizeGroup.addView(quarterTotalRadio);
        sizeGroup.addView(avail80Radio);
        sizeGroup.check(quarterTotalRadio.getId());
        addView(sizeGroup);

        addSectionTitle("Duration in minutes / 测试时长（分钟）");
        durationMinutesInput = new EditText(this);
        durationMinutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        durationMinutesInput.setHint("Blank = run until stopped / 留空表示无限运行");
        addView(durationMinutesInput);
    }

    @Override
    protected String soName() {
        return "libmemtester.so";
    }

    @Override
    protected String[] buildArgs() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long sizeBytes = avail80Radio.isChecked()
                ? (long) (mi.availMem * 0.8)
                : mi.totalMem / 4;
        long sizeMb = sizeBytes / (1024 * 1024);
        return new String[]{sizeMb + "M"};
    }

    @Override
    protected Long testDurationMs() {
        String text = durationMinutesInput.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            long minutes = Long.parseLong(text);
            return minutes > 0 ? minutes * 60_000L : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected String failureKeyword() {
        return "FAILURE";
    }

    @Override
    protected String description() {
        return "Runs the open-source memtester binary against the memory size and duration "
                + "selected below (leave duration blank to run until stopped).\n"
                + "运行开源 memtester 工具，测试容量与时长见下方选择"
                + "（时长留空则无限运行直到点击停止）。";
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run from `EloDeviceTester/`:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ddr/MemtesterActivity.java
git commit -m "feat: add size/duration controls to Memtester test page"
```

---

### Task 3: On-device manual verification

No code changes — confirms the new controls behave as designed and that QMESA's inherited color/hh:mm:ss changes work, without regressing existing behavior. Requires a connected arm64-v8a Android device (API 26+) with USB debugging enabled.

**Files:** none (verification only).

- [ ] **Step 1: Install and open Memtester**

```bash
./gradlew installDebug
adb shell am start -n com.elotouch.devicetester/.MainActivity
```

On the device: DDR module → Memtester "Enter Test". Confirm the page shows, top to bottom: description → "Test Size" radio group (with "1/4 Total RAM" checked by default) → "Duration in minutes" input (empty, with hint text) → status line → Start/Stop → log box.

- [ ] **Step 2: Verify size selection changes the actual test size**

With "1/4 Total RAM" selected, tap Start, let a couple of log lines appear, then Stop. Note the reported size in the log (memtester prints the total size near the top of its output). Restart the page (back then re-enter, or just re-tap Start after switching the radio to "80% Available RAM"), tap Start again, and confirm the log now reports a different size consistent with 80% of available RAM rather than 1/4 of total RAM.

- [ ] **Step 3: Verify duration auto-stop**

Select either size radio, type `1` into the duration field, tap Start. Confirm the test automatically stops around 60 seconds later (watch the elapsed-time line) without tapping Stop, and the final status line reads "PASSED" in green (assuming no FAILURE was logged) with an elapsed time close to `00:01:0x`.

- [ ] **Step 4: Verify blank duration still runs until manually stopped**

Clear the duration field, tap Start, wait ~10 seconds, tap Stop manually. Confirm the test does NOT auto-stop at 60 seconds and DOES stop immediately when Stop is tapped, with elapsed time reflecting the actual run length in `hh:mm:ss` format (e.g. `00:00:1x`).

- [ ] **Step 5: Verify invalid duration input falls back to infinite**

Type `abc` into the duration field, tap Start, wait ~10 seconds, confirm the test is still running (no auto-stop), then tap Stop to end it. Repeat quickly with `0` and a negative number if desired — same expected behavior (no auto-stop).

- [ ] **Step 6: Verify FAILED renders in red**

If a failure can be reproduced (e.g. picking a size larger than available RAM causes memtester to report `FAILURE`, or by reviewing a captured log from a known-bad run), confirm the final status text renders in red rather than green.

- [ ] **Step 7: Verify QMESA still works with inherited changes**

Navigate to DDR → QMESA "Enter Test". Confirm the page has no size/duration controls (unchanged layout) but the elapsed-time line during a run and the final PASSED/FAILED status (if reachable on this device) show `hh:mm:ss` and are colored the same as Memtester's.

No commit for this task (verification only). If any step fails, fix the relevant file from Task 1 or Task 2 and re-run this task's checks before considering the feature done.

---

### Task 4: Lock size/duration controls while a test is running

Discovered during Task 3 manual verification: the size RadioGroup and duration EditText remain editable while a test is running, letting the user change them mid-run with no effect until the next Start. They must lock as soon as Start is pressed and unlock only when the test finishes (Stop pressed or the process ends on its own).

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ddr/MemtesterActivity.java`

**Interfaces:**
- Consumes: nothing new from outside this task.
- Produces: `protected void setExtraControlsEnabled(boolean enabled)` on `NativeToolTestActivity` — no-op by default, called with `false` at the same point `start()` disables `startButton`/enables `stopButton`, and with `true` at the same point `onFinished()` re-enables `startButton`/disables `stopButton`. `MemtesterActivity` overrides it to toggle its three controls.

- [ ] **Step 1: Add the hook and wire it into `start()`/`onFinished()` in `NativeToolTestActivity.java`**

Add this method next to the existing `testDurationMs()` hook:

```java
    /**
     * Called with false when Start is pressed (disable extra controls while
     * running) and true when the test finishes (re-enable them). Default: no-op.
     */
    protected void setExtraControlsEnabled(boolean enabled) {
    }
```

In `start()`, immediately after the line `stopButton.setEnabled(true);`, add:

```java
        setExtraControlsEnabled(false);
```

In `onFinished()`, immediately after the line `stopButton.setEnabled(false);`, add:

```java
        setExtraControlsEnabled(true);
```

- [ ] **Step 2: Override the hook in `MemtesterActivity.java`**

Add this method (anywhere among the other overrides, e.g. right after `buildExtraControls()`):

```java
    @Override
    protected void setExtraControlsEnabled(boolean enabled) {
        quarterTotalRadio.setEnabled(enabled);
        avail80Radio.setEnabled(enabled);
        durationMinutesInput.setEnabled(enabled);
    }
```

- [ ] **Step 3: Build to verify it compiles**

Run from `EloDeviceTester/`:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java app/src/main/java/com/elotouch/devicetester/modules/ddr/MemtesterActivity.java
git commit -m "feat: lock Memtester size/duration controls while a test is running"
```

- [ ] **Step 5: Manual on-device check**

Install (`./gradlew installDebug`), open DDR → Memtester, tap Start. Confirm the "1/4 Total RAM"/"80% Available RAM" radios and the duration EditText all render visually disabled and do not respond to taps while the status line reads "Running...". Tap Stop (or let the test finish on its own). Confirm all three controls become tappable again once the status line shows the final PASSED/FAILED result.
