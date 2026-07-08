# DDR Native Tool Tests: Refactor to Dedicated Activities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the memtester and QMESA native-tool tests out of inline sections in `DdrActivity` into two dedicated Activities, fixing the on-device-confirmed issue where a long native-tool log line pushes the Stop button off-screen.

**Architecture:** Extract the existing (already fixed and reviewed) `NativeTestSection` inner-class logic from `DdrActivity` into a new abstract `NativeToolTestActivity extends BaseTestActivity` base class, with `MemtesterActivity` and `QmesaActivity` as thin concrete subclasses supplying only the tool-specific bits (binary name, args, failure keyword, description). `DdrActivity` shrinks to two "Enter Test" launcher buttons. Start/Stop move above the log box in the new layout, and the finished/stopped status text now reports final elapsed time.

**Tech Stack:** Java (Android, minSdk 26), no test framework in this repo — manual on-device verification.

## Global Constraints

- Language: Java only (no Kotlin).
- Every long-running test needs a Stop button that actually kills the OS process (`NativeProcessRunner.stop()`), not just a flag.
- Missing capability (non-arm64-v8a CPU) must grey out the affected control, never crash — `NativeProcessRunner.isArm64Supported()` gate must be preserved exactly.
- The already-fixed on-device bugs must NOT regress during this extraction: (1) log updates must stay coalesced/batched (no per-line `ui()` post — this is what prevented the ANR); (2) display lines must stay tail-truncated to 200 chars (failure-keyword scan must still run on the FULL untruncated line); (3) a user-initiated Stop must never be reported as "Failed to start" (relies on `NativeProcessRunner`'s `stopRequested` flag, already implemented — do not touch `NativeProcessRunner.java` in this plan).
- New requirement: Start and Stop buttons render ABOVE the log box (previously below) — this is what keeps them reachable regardless of log height.
- New requirement: the finished/stopped status message includes the final elapsed time (format `mm:ss`), for both PASSED and FAILED outcomes.
- Mutual-exclusion logic (the `other` field cross-disabling the other section's Start button) is REMOVED — no longer needed since each tool is now its own Activity and Android only shows one foreground Activity at a time.
- `core/NativeProcessRunner.java` is NOT modified by this plan — it already has the `stopRequested`-based fix and is out of scope here.
- Current `DdrActivity.java` is 352 lines (verified 2026-07-08) with a `NativeTestSection` inner class implementing all of the above; this plan extracts that inner class's logic, it does not re-derive it from scratch.
- Design reference: `docs/superpowers/specs/2026-07-07-ddr-memtester-qmesa-design.md` (§3 "UI 架构（2026-07-08 修订版）" and the "真机验证发现的问题与修复" section).

---

### Task 1: Extract `NativeToolTestActivity` base class + `MemtesterActivity`/`QmesaActivity` + simplify `DdrActivity` + register in manifest

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java`
- Create: `app/src/main/java/com/elotouch/devicetester/modules/ddr/MemtesterActivity.java`
- Create: `app/src/main/java/com/elotouch/devicetester/modules/ddr/QmesaActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ddr/DdrActivity.java` (full-file replace, see below)
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `com.elotouch.devicetester.core.NativeProcessRunner` (unchanged — `isArm64Supported()`, `run(Context, String, String[], LineListener)`, `stop()`) and `BaseTestActivity` protected members (`addInfo`, `addButton`, `runAsync`, `ui`, `main`, `title()`/`buildUi()`/`onStopTests()` hooks).
- Produces: `NativeToolTestActivity` — an abstract class with four `protected abstract` hooks (`soName()`, `buildArgs()`, `failureKeyword()`, `description()`) that `MemtesterActivity` and `QmesaActivity` implement. Nothing outside this task consumes these classes except `DdrActivity`, which only references them by class literal (`MemtesterActivity.class`, `QmesaActivity.class`) to build `Intent`s.

- [ ] **Step 1: Create `NativeToolTestActivity.java`**

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
 * renders a description, a status/elapsed-time line, Start/Stop buttons, and
 * a rolling terminal-style log below them, then launches the tool via
 * {@link NativeProcessRunner} and reports a final PASSED/FAILED status with
 * elapsed time when stopped or finished.
 */
public abstract class NativeToolTestActivity extends BaseTestActivity {

    private static final int MAX_DISPLAY_LINE_LENGTH = 200;

    /** Bundled executable name under jniLibs/arm64-v8a/, e.g. "libmemtester.so". */
    protected abstract String soName();

    /** Command-line arguments; computed fresh each time Start is pressed. */
    protected abstract String[] buildArgs();

    /** Substring marking a failure in the tool's output, e.g. "FAILURE" or "FAILED". */
    protected abstract String failureKeyword();

    /** Bilingual description shown at the top of the page. */
    protected abstract String description();

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

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
            statusText.setText(String.format(Locale.US,
                    "%s  Elapsed 已测试时间: %02d:%02d",
                    failureSeen ? "FAILURE detected 检测到失败" : "Running... 运行中",
                    elapsed / 60, elapsed % 60));
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void buildUi() {
        addInfo(description());
        statusText = addInfo("Tap Start. 点击开始。");
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
        lastLines.clear();
        synchronized (logBufferLock) {
            pendingLines.clear();
            uiFlushScheduled = false;
        }
        startTimeMs = System.currentTimeMillis();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        logText.setText("");
        statusText.setText("Running... 运行中");
        main.post(tick);

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

    private void onFinished() {
        running = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
        String elapsedStr = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60);
        if (startFailure != null) {
            statusText.setText("Failed to start 启动失败: " + startFailure);
        } else if (failureSeen) {
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败，已测试时间 %s，请查看日志 / see log below, elapsed %s",
                    elapsedStr, elapsedStr));
        } else {
            statusText.setText(String.format(Locale.US,
                    "PASSED 测试完成，已测试时间 %s / no errors detected, elapsed %s",
                    elapsedStr, elapsedStr));
        }
    }

    @Override
    protected void onStopTests() {
        runner.stop();
    }
}
```

- [ ] **Step 2: Create `MemtesterActivity.java`**

```java
package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Runs the open-source memtester binary (PRD DDR §3.1 native-tool addendum)
 * against ~1/4 of total RAM, capped at 80% of available RAM.
 */
public class MemtesterActivity extends NativeToolTestActivity {

    @Override
    protected String title() {
        return "Memtester";
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
        long sizeBytes = Math.min(mi.totalMem / 4, (long) (mi.availMem * 0.8));
        long sizeMb = sizeBytes / (1024 * 1024);
        return new String[]{sizeMb + "M"};
    }

    @Override
    protected String failureKeyword() {
        return "FAILURE";
    }

    @Override
    protected String description() {
        return "Runs the open-source memtester binary against ~1/4 of total RAM "
                + "(capped at 80% of available RAM), looping forever until stopped.\n"
                + "运行开源 memtester 工具，测试容量约为总内存的 1/4（不超过可用内存的 80%），"
                + "无限循环直到点击 Stop。";
    }
}
```

- [ ] **Step 3: Create `QmesaActivity.java`**

```java
package com.elotouch.devicetester.modules.ddr;

/**
 * Runs the vendor QMESA memory stress tool (PRD DDR §3.1 native-tool addendum)
 * with the fixed 8-16MB / 4-thread / 10000s configuration.
 */
public class QmesaActivity extends NativeToolTestActivity {

    @Override
    protected String title() {
        return "QMESA";
    }

    @Override
    protected String soName() {
        return "libqmesa64.so";
    }

    @Override
    protected String[] buildArgs() {
        return new String[]{"-startSize", "8MB", "-endSize", "8MB", "-totalSize", "16MB",
                "-errorCheck", "T", "-secs", "10000", "-numThreads", "4"};
    }

    @Override
    protected String failureKeyword() {
        return "FAILED";
    }

    @Override
    protected String description() {
        return "Runs the vendor QMESA stress tool with an 8-16MB working set across "
                + "4 threads, for up to ~2.7 hours or until stopped.\n"
                + "运行厂商 QMESA 压力测试工具（8-16MB 工作集，4 线程），最长约 2.7 小时或手动停止。";
    }
}
```

- [ ] **Step 4: Replace `DdrActivity.java` with the following full content**

```java
package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DDR / RAM module (PRD §3.1): capacity info, read-write bandwidth (relative
 * reference value), a safe stress-fill test, and launcher buttons for two
 * bundled native diagnostic tools (memtester, QMESA), each its own Activity.
 */
public class DdrActivity extends BaseTestActivity {

    private TextView infoText;
    private TextView bandwidthText;
    private TextView stressText;

    /** Held memory blocks for the stress test; cleared on stop. */
    private final List<byte[]> blocks = new ArrayList<>();

    @Override
    protected String title() {
        return "DDR / Memory 内存测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Capacity & Info / 容量与信息");
        infoText = addInfo("");
        refreshInfo();
        addButton("Refresh Info / 刷新内存信息", this::refreshInfo);

        addSectionTitle("Read/Write Bandwidth (relative) / 读写带宽（相对参考值）");
        bandwidthText = addInfo("Tap to start. 点击开始测试。");
        addButton("Start Bandwidth Test / 开始带宽测试", this::runBandwidth);

        addSectionTitle("Stress Fill (safe threshold) / 压力填充（安全阈值保护）");
        stressText = addInfo("Allocates up to ~50% of free RAM (capped by heap) to test "
                + "stability under load.\n将分配至多约 50% 可用内存（不超过堆上限），检测极限负载稳定性。");
        addButton("Start Stress Test / 开始压力测试", this::runStress);
        addButton("Stop / Free Memory / 停止并释放内存", () -> {
            stopTests();
            releaseBlocks();
            ui(() -> stressText.setText("Stopped and memory freed. 已停止并释放内存。"));
        });

        addSectionTitle("Memtester (native tool 原生工具)");
        addInfo("Runs the open-source memtester binary against ~1/4 of total RAM "
                + "(capped at 80% of available RAM), looping forever until stopped.\n"
                + "运行开源 memtester 工具，测试容量约为总内存的 1/4（不超过可用内存的 80%），"
                + "无限循环直到点击 Stop。");
        addButton("Enter Test / 进入测试",
                () -> startActivity(new Intent(this, MemtesterActivity.class)));

        addSectionTitle("QMESA (native tool 原生工具)");
        addInfo("Runs the vendor QMESA stress tool with an 8-16MB working set across "
                + "4 threads, for up to ~2.7 hours or until stopped.\n"
                + "运行厂商 QMESA 压力测试工具（8-16MB 工作集，4 线程），最长约 2.7 小时或手动停止。");
        addButton("Enter Test / 进入测试",
                () -> startActivity(new Intent(this, QmesaActivity.class)));
    }

    private void refreshInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long maxHeap = Runtime.getRuntime().maxMemory();
        infoText.setText(String.format(Locale.US,
                "Total RAM 总运行内存：%s\nAvailable RAM 可用内存：%s\n"
                        + "Low-memory threshold 低内存阈值：%s\nPer-process heap 单进程堆上限：%s",
                fmt(mi.totalMem), fmt(mi.availMem), fmt(mi.threshold), fmt(maxHeap)));
    }

    private void runBandwidth() {
        bandwidthText.setText("Testing… 测试中…");
        runAsync(() -> {
            final int sizeMb = 64;
            final int n = sizeMb * 1024 * 1024;
            byte[] src = new byte[n];
            byte[] dst = new byte[n];
            for (int i = 0; i < n; i += 4096) src[i] = (byte) i; // touch pages

            // Write bandwidth: fill dst repeatedly.
            int rounds = 5;
            long t0 = System.nanoTime();
            for (int r = 0; r < rounds && !isStopped(); r++) {
                java.util.Arrays.fill(dst, (byte) r);
            }
            long writeNs = System.nanoTime() - t0;

            // Copy bandwidth: src -> dst.
            t0 = System.nanoTime();
            for (int r = 0; r < rounds && !isStopped(); r++) {
                System.arraycopy(src, 0, dst, 0, n);
            }
            long copyNs = System.nanoTime() - t0;

            double mb = (double) sizeMb * rounds;
            double writeSpeed = mb / (writeNs / 1e9);
            double copySpeed = mb / (copyNs / 1e9);
            ui(() -> bandwidthText.setText(String.format(Locale.US,
                    "Sequential write 顺序写入：%.0f MB/s\nMemory copy 内存拷贝：%.0f MB/s\n"
                            + "(Relative Java figure, not physical bandwidth. 纯 Java 综合相对值，非物理带宽)",
                    writeSpeed, copySpeed)));
        });
    }

    private void runStress() {
        stressText.setText("Stress filling… 压力填充中…");
        runAsync(() -> {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);

            long maxHeap = Runtime.getRuntime().maxMemory();
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            // Safety threshold: stay under both 50% available RAM and 70% of heap.
            long target = Math.min((long) (mi.availMem * 0.5), (long) (maxHeap * 0.7) - used);
            if (target < 0) target = 0;

            final int chunk = 8 * 1024 * 1024; // 8MB
            long allocated = 0;
            try {
                while (allocated < target && !isStopped()) {
                    byte[] b = new byte[chunk];
                    for (int i = 0; i < chunk; i += 4096) b[i] = 1; // commit pages
                    blocks.add(b);
                    allocated += chunk;
                    final long done = allocated;
                    final long tgt = target;
                    ui(() -> stressText.setText(String.format(Locale.US,
                            "Allocated 已稳定分配：%s / target 目标 %s", fmt(done), fmt(tgt))));
                    Thread.sleep(40);
                }
                final long done = allocated;
                ui(() -> stressText.setText(String.format(Locale.US,
                        "Done: stably allocated %s without crash. Tap Stop to release.\n"
                                + "压力测试完成，稳定分配 %s 未崩溃。点击\"停止并释放\"回收。", fmt(done), fmt(done))));
            } catch (OutOfMemoryError oom) {
                releaseBlocks();
                ui(() -> stressText.setText("Near memory limit; auto-released to avoid OOM. "
                        + "接近内存上限，已自动释放以避免 OOM。"));
            } catch (InterruptedException ignored) {
            }
        });
    }

    private void releaseBlocks() {
        blocks.clear();
        System.gc();
    }

    @Override
    protected void onStopTests() {
        releaseBlocks();
    }

    private static String fmt(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        return String.format(Locale.US, "%.0f MB", mb);
    }
}
```

- [ ] **Step 5: Register the two new Activities in `AndroidManifest.xml`**

Find this line (currently at `app/src/main/AndroidManifest.xml:73`):

```xml
        <activity android:name=".modules.ddr.DdrActivity" />
```

Change it to:

```xml
        <activity android:name=".modules.ddr.DdrActivity" />
        <activity android:name=".modules.ddr.MemtesterActivity" />
        <activity android:name=".modules.ddr.QmesaActivity" />
```

- [ ] **Step 6: Build to verify it compiles**

Run from `EloDeviceTester/`:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java app/src/main/java/com/elotouch/devicetester/modules/ddr/MemtesterActivity.java app/src/main/java/com/elotouch/devicetester/modules/ddr/QmesaActivity.java app/src/main/java/com/elotouch/devicetester/modules/ddr/DdrActivity.java app/src/main/AndroidManifest.xml
git commit -m "refactor: move memtester/QMESA native tests into dedicated Activities"
```

---

### Task 2: On-device manual verification

No code changes — confirms the refactor works on hardware and that the previously-fixed bugs (ANR, unbounded log height, Stop-misreported-as-failure) have not regressed. Requires a connected arm64-v8a Android device (API 26+) with USB debugging enabled.

**Files:** none (verification only).

- [ ] **Step 1: Install and navigate**

```bash
./gradlew installDebug
adb shell am start -n com.elotouch.devicetester/.MainActivity
```

On the device: DDR module → confirm two "Enter Test / 进入测试" buttons under "Memtester" and "QMESA" sections (no inline Start/Stop/log on this page anymore). Tap the Memtester one.

Expected: a new page opens titled "Memtester", with description → status line → Start button → Stop button → black terminal-style log box, in that top-to-bottom order (Start/Stop above the log, not below).

- [ ] **Step 2: Run memtester and confirm no ANR / bounded layout**

Tap Start. Let it run at least 30 seconds (long enough to reach subtests like "Solid Bits" that previously produced very long single lines). Confirm via a separate shell:

```bash
adb logcat -d | grep -iE "ANR in com.elotouch|not responding"
```

Expected: no output (no ANR). Also confirm visually that the Start/Stop buttons remain at a fixed position near the top of the screen throughout (not pushed down as the log grows), and the log box shows at most 10 lines, each capped in length.

- [ ] **Step 3: Stop and confirm correct final status**

Tap Stop. Expected within ~1 second:
- Status line shows "PASSED 测试完成，已测试时间 MM:SS / no errors detected, elapsed MM:SS" (or FAILED equivalent if a failure keyword was seen) — NOT "Failed to start".
- `adb shell ps -A | grep -i memtester` returns nothing (process killed).
- Start button re-enabled, Stop disabled.

- [ ] **Step 4: Back-button lifecycle check**

Tap Start again, then press the device Back button to leave the Activity while it's running. Confirm `adb shell ps -A | grep -i memtester` returns nothing shortly after (via `onStopTests()`).

- [ ] **Step 5: Repeat Steps 1-4 for QMESA**

Navigate to the QMESA "Enter Test" button and repeat the same checks (`adb shell ps -A | grep -i qmesa` for cleanup checks — note: a pre-existing unrelated OEM `QMESA_64` process may already be running on this device under the `shell` user; only care about a process owned by the app's own UID). If QMESA still produces no output/process when launched from the app (the known open issue noted in the design doc), record that as expected-for-now and move on — it is tracked separately, not a regression from this refactor.

No commit for this task (verification only). If Steps 2-4 fail for memtester (a regression in the ported logic), fix `NativeToolTestActivity.java` and re-run this task's checks before proceeding.
