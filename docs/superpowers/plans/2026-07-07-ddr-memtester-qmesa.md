# DDR Memtester / QMESA Native Tool Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two native-binary diagnostic tests (memtester, QMESA) to the DDR module's existing test page.

**Architecture:** Bundle the two prebuilt arm64-v8a executables as `jniLibs/arm64-v8a/*.so` (so Android extracts them to an executable directory, working around API 29+ W^X restrictions), add a small generic `core.NativeProcessRunner` that launches a bundled binary and streams its stdout line by line, and add a reusable `NativeTestSection` inner helper inside `DdrActivity` that both new sections (memtester, QMESA) instantiate for their UI + start/stop/timer/log behavior.

**Tech Stack:** Java (Android, minSdk 26, targetSdk/compileSdk 34), Gradle 8.5.2 AGP, no test framework in this repo (manual on-device verification, matching existing project convention — see `EloDeviceTester/README.md`: "No automated tests yet").

## Global Constraints

- Language: Java only (no Kotlin) — per `CLAUDE.md`.
- Only arm64-v8a needs to be supported for these two tools (confirmed with user; no armeabi-v7a fallback).
- No blocking work on the UI thread; every long-running test needs a Stop button (`CLAUDE.md` constraint #3) — both sections must run through `BaseTestActivity.runAsync`/off-thread and support a real Stop that kills the OS process, not just a flag.
- Missing capability (here: non-arm64 CPU) must be greyed out / non-tappable, never silently crash (`CLAUDE.md` constraint #1).
- `app/build.gradle` currently has `compileSdk 34` / `minSdk 26` / `targetSdk 34` and no `packaging` block (verified 2026-07-07).
- Source binaries (verified present, 2026-07-07): `EloTestApp/memtester` (22976 bytes, arm64-v8a, dynamically linked, memtester 4.3.0) and `EloTestApp/QMESA_64` (965003 bytes, arm64-v8a, statically linked). Both live one directory above `EloDeviceTester/` (i.e. `../memtester`, `../QMESA_64` relative to the Gradle project root).
- Design reference: `docs/superpowers/specs/2026-07-07-ddr-memtester-qmesa-design.md`.

---

### Task 1: Bundle native binaries via jniLibs + Gradle packaging config

**Files:**
- Create: `app/src/main/jniLibs/arm64-v8a/libmemtester.so` (binary copy of `../memtester`)
- Create: `app/src/main/jniLibs/arm64-v8a/libqmesa64.so` (binary copy of `../QMESA_64`)
- Modify: `app/build.gradle:24-27`
- Modify: `README.md` (append one bullet to the existing quirks list)

**Interfaces:**
- Produces: at runtime, `context.getApplicationInfo().nativeLibraryDir + "/libmemtester.so"` and `.../libqmesa64.so` resolve to real, executable files on disk. Task 2 (`NativeProcessRunner`) depends on this.

- [ ] **Step 1: Copy and rename the binaries into jniLibs**

Run from the `EloDeviceTester/` directory:

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../memtester app/src/main/jniLibs/arm64-v8a/libmemtester.so
cp ../QMESA_64 app/src/main/jniLibs/arm64-v8a/libqmesa64.so
ls -la app/src/main/jniLibs/arm64-v8a/
```

Expected: two files listed, `libmemtester.so` exactly 22976 bytes and `libqmesa64.so` exactly 965003 bytes (byte-for-byte copies of the originals — do NOT open/save these through a text editor, that will corrupt the binary).

- [ ] **Step 2: Add the packaging block to build.gradle**

In `app/build.gradle`, the `android { ... }` block currently ends with:

```gradle
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
```

Change it to:

```gradle
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

This forces Gradle to extract native libraries to disk at install time (`ApplicationInfo.nativeLibraryDir`) instead of the newer default of mapping them directly from inside the APK — without this, there is no real file on disk for `ProcessBuilder` to execute.

- [ ] **Step 3: Build and verify the binaries land in the APK as executables**

Run from `EloDeviceTester/`:

```bash
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/EloDeviceTester.apk | grep -i "lib/arm64-v8a"
```

Expected: the build succeeds, and the `unzip -l` output includes two lines for `lib/arm64-v8a/libmemtester.so` (size 22976) and `lib/arm64-v8a/libqmesa64.so` (size 965003).

- [ ] **Step 4: Document the packaging quirk in README**

In `README.md`, find the "quirks worth knowing" bullet list that currently includes:

```markdown
- **DDR bandwidth** — Java array copy is a *relative* figure, not physical bandwidth.
```

Add a new bullet directly after it:

```markdown
- **DDR memtester / QMESA** — bundled prebuilt arm64-v8a native binaries, packaged as
  `jniLibs/arm64-v8a/lib*.so` with `packaging.jniLibs.useLegacyPackaging = true` in
  `app/build.gradle` so they're extracted to `nativeLibraryDir` and remain executable
  under API 29+ W^X restrictions (raw `assets/` + runtime `chmod` does NOT work on
  this app's `targetSdk 34`).
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/jniLibs/arm64-v8a/libmemtester.so app/src/main/jniLibs/arm64-v8a/libqmesa64.so app/build.gradle README.md
git commit -m "feat: bundle memtester/QMESA native binaries via jniLibs"
```

---

### Task 2: `core.NativeProcessRunner` component

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/core/NativeProcessRunner.java`

**Interfaces:**
- Consumes: nothing project-specific (only `android.content.Context`, `android.os.Build`, `java.lang.ProcessBuilder`).
- Produces (used by Task 3):
  - `static boolean NativeProcessRunner.isArm64Supported()`
  - `void run(Context context, String soName, String[] args, NativeProcessRunner.LineListener onLine) throws IOException` — **blocking**, must be called off the UI thread.
  - `void stop()` — safe to call from any thread; destroys the running process if any, which unblocks a pending `run()`.
  - `interface LineListener { void onLine(String line); }`

- [ ] **Step 1: Write the class**

Create `app/src/main/java/com/elotouch/devicetester/core/NativeProcessRunner.java`:

```java
package com.elotouch.devicetester.core;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a native executable bundled under jniLibs/arm64-v8a/ (see
 * app/build.gradle's packaging.jniLibs.useLegacyPackaging), streaming its
 * combined stdout/stderr line by line. Used for third-party diagnostic
 * tools (memtester, QMESA) that ship as prebuilt ARM binaries rather than
 * Java code.
 */
public class NativeProcessRunner {

    public interface LineListener {
        void onLine(String line);
    }

    private volatile Process process;

    public static boolean isArm64Supported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    /**
     * Launches {@code nativeLibraryDir/soName args...} and blocks, delivering
     * each output line to {@code onLine}, until the process exits or
     * {@link #stop()} kills it. Must be called off the UI thread.
     */
    public void run(Context context, String soName, String[] args, LineListener onLine)
            throws IOException {
        String exePath = context.getApplicationInfo().nativeLibraryDir + "/" + soName;
        List<String> command = new ArrayList<>();
        command.add(exePath);
        for (String a : args) command.add(a);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.onLine(line);
            }
        } finally {
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    /** Kills the running process, if any, unblocking a pending {@link #run}. */
    public void stop() {
        Process p = process;
        if (p != null) p.destroy();
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run from `EloDeviceTester/`:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (This class has no unit tests — the project has no test source set; its behavior is exercised end-to-end in Task 3's manual verification.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/core/NativeProcessRunner.java
git commit -m "feat: add NativeProcessRunner for launching bundled native diagnostic tools"
```

---

### Task 3: Wire memtester + QMESA sections into DdrActivity

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ddr/DdrActivity.java` (full-file replace, see below)

**Interfaces:**
- Consumes: `NativeProcessRunner` (Task 2) — `isArm64Supported()`, `run(...)`, `stop()`, `LineListener`.
- Consumes: `BaseTestActivity` protected members — `addSectionTitle`, `addInfo`, `addButton`, `runAsync`, `ui`, `main` (the `Handler`).
- Produces: nothing consumed elsewhere — this is the leaf UI.

- [ ] **Step 1: Replace `DdrActivity.java` with the following full content**

```java
package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;
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
import java.util.function.Supplier;

/**
 * DDR / RAM module (PRD §3.1): capacity info, read-write bandwidth (relative
 * reference value), a safe stress-fill test, and two bundled native
 * diagnostic tools (memtester, QMESA).
 */
public class DdrActivity extends BaseTestActivity {

    private TextView infoText;
    private TextView bandwidthText;
    private TextView stressText;

    /** Held memory blocks for the stress test; cleared on stop. */
    private final List<byte[]> blocks = new ArrayList<>();

    private NativeTestSection memtesterSection;
    private NativeTestSection qmesaSection;

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
        memtesterSection = new NativeTestSection("libmemtester.so", this::memtesterArgs, "FAILURE");
        memtesterSection.render(
                "Runs the open-source memtester binary against ~1/4 of total RAM "
                        + "(capped at 80% of available RAM), looping forever until stopped.\n"
                        + "运行开源 memtester 工具，测试容量约为总内存的 1/4（不超过可用内存的 80%），"
                        + "无限循环直到点击 Stop。");

        addSectionTitle("QMESA (native tool 原生工具)");
        qmesaSection = new NativeTestSection("libqmesa64.so", DdrActivity::qmesaArgs, "FAILED");
        qmesaSection.render(
                "Runs the vendor QMESA stress tool with an 8-16MB working set across "
                        + "4 threads, for up to ~2.7 hours or until stopped.\n"
                        + "运行厂商 QMESA 压力测试工具（8-16MB 工作集，4 线程），最长约 2.7 小时或手动停止。");

        memtesterSection.other = qmesaSection;
        qmesaSection.other = memtesterSection;
    }

    private String[] memtesterArgs() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long sizeBytes = Math.min(mi.totalMem / 4, (long) (mi.availMem * 0.8));
        long sizeMb = sizeBytes / (1024 * 1024);
        return new String[]{sizeMb + "M"};
    }

    private static String[] qmesaArgs() {
        return new String[]{"-startSize", "8MB", "-endSize", "8MB", "-totalSize", "16MB",
                "-errorCheck", "T", "-secs", "10000", "-numThreads", "4"};
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
        if (memtesterSection != null) memtesterSection.stop();
        if (qmesaSection != null) qmesaSection.stop();
    }

    private static String fmt(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        return String.format(Locale.US, "%.0f MB", mb);
    }

    /**
     * One native-binary test block (memtester / QMESA): renders its own UI,
     * launches the binary via {@link NativeProcessRunner}, and shows a
     * rolling 10-line log plus an elapsed-time / pass-fail status line.
     * {@link #other} is wired up after construction so the two sections can
     * disable each other's Start button while one is running (they share
     * {@link BaseTestActivity}'s single-thread executor).
     */
    private final class NativeTestSection {
        private final String soName;
        private final Supplier<String[]> argsSupplier;
        private final String failureKeyword;
        private final NativeProcessRunner runner = new NativeProcessRunner();
        private final Deque<String> lastLines = new ArrayDeque<>();

        private TextView statusText;
        private TextView logText;
        private Button startButton;
        private Button stopButton;

        private boolean running;
        private volatile boolean failureSeen;
        private long startTimeMs;

        NativeTestSection other;

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

        NativeTestSection(String soName, Supplier<String[]> argsSupplier, String failureKeyword) {
            this.soName = soName;
            this.argsSupplier = argsSupplier;
            this.failureKeyword = failureKeyword;
        }

        void render(String description) {
            addInfo(description);
            statusText = addInfo("Tap Start. 点击开始。");
            logText = addInfo("");
            logText.setTypeface(Typeface.MONOSPACE);
            startButton = addButton("Start / 开始", this::start);
            stopButton = addButton("Stop / 停止", this::stop);
            stopButton.setEnabled(false);

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
            lastLines.clear();
            startTimeMs = System.currentTimeMillis();
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            if (other != null) other.startButton.setEnabled(false);
            logText.setText("");
            statusText.setText("Running... 运行中");
            main.post(tick);

            String[] args = argsSupplier.get();
            runAsync(() -> {
                try {
                    runner.run(DdrActivity.this, soName, args, this::onLine);
                } catch (IOException e) {
                    ui(() -> statusText.setText("Failed to start 启动失败: " + e.getMessage()));
                }
                ui(this::onFinished);
            });
        }

        private void stop() {
            runner.stop();
        }

        private void onLine(String line) {
            if (line.contains(failureKeyword)) failureSeen = true;
            ui(() -> {
                if (lastLines.size() >= 10) lastLines.removeFirst();
                lastLines.addLast(line);
                StringBuilder sb = new StringBuilder();
                for (String l : lastLines) sb.append(l).append('\n');
                logText.setText(sb.toString());
            });
        }

        private void onFinished() {
            running = false;
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            if (other != null) other.startButton.setEnabled(true);
            statusText.setText(failureSeen
                    ? "FAILED 测试失败，请查看日志 / see log below"
                    : "PASSED 测试完成，未发现错误 / no errors detected");
        }
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
git add app/src/main/java/com/elotouch/devicetester/modules/ddr/DdrActivity.java
git commit -m "feat: wire memtester and QMESA native tests into DDR module"
```

---

### Task 4: On-device manual verification

No code changes — this task confirms the feature actually works on hardware, per the design's Testing Plan. Requires a connected arm64-v8a Android device (API 26+) with USB debugging enabled.

**Files:** none (verification only).

- [ ] **Step 1: Install and open the DDR module**

```bash
./gradlew installDebug
adb shell am start -n com.elotouch.devicetester/.MainActivity
```

On the device, tap the DDR module tile, scroll to the bottom. Expected: "Memtester (native tool 原生工具)" and "QMESA (native tool 原生工具)" sections are visible, each with Start (enabled) and Stop (disabled) buttons.

- [ ] **Step 2: Run memtester and confirm live behavior**

Tap memtester's Start button. Expected within a few seconds:
- Status line shows "Running... 运行中  Elapsed 已测试时间: 00:0X" and the seconds keep ticking up once per second.
- The log box below starts showing memtester output lines (e.g. `Loop 1/0:`, `  Stuck Address       : ok`), capped at the 10 most recent lines.
- QMESA's Start button is now disabled (greyed out).

- [ ] **Step 3: Stop memtester and confirm cleanup**

Tap memtester's Stop button. Expected within ~1 second:
- Status line stops ticking and shows either "PASSED 测试完成，未发现错误 / no errors detected" or, if a `FAILURE` line was seen, "FAILED 测试失败，请查看日志 / see log below".
- Memtester's Start button re-enables; Stop disables.
- QMESA's Start button re-enables.
- Confirm no leaked process: `adb shell ps -A | grep -i memtester` returns nothing.

- [ ] **Step 4: Repeat Steps 2-3 for QMESA**

Tap QMESA's Start button; confirm the same live-timer/log/mutual-exclusion behavior (memtester's Start disabled while QMESA runs), then Stop and confirm `adb shell ps -A | grep -i qmesa` returns nothing after stopping.

- [ ] **Step 5: Confirm lifecycle safety (background/leave page)**

Start QMESA, then press the device Back button (or Home) to leave `DdrActivity` while it's still running. Expected: `adb shell ps -A | grep -i qmesa` returns nothing shortly after leaving the page (confirms `onStopTests()` kills the process on pause).

- [ ] **Step 6: Confirm ABI gate on a non-arm64 target (if available)**

If an x86_64 emulator image is available, install and open the DDR module there. Expected: both native sections show "Unsupported CPU architecture (requires arm64-v8a). 当前设备架构不支持 arm64-v8a 原生工具。" with Start disabled. If no non-arm64 test target is available, skip this step and note it as unverified.

No commit for this task (verification only, no code changes). If any step fails, fix the underlying issue in the relevant task's file and re-run that task's build + this task's manual steps before proceeding.
