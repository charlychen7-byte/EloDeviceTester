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
        private String startFailure;

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
            startFailure = null;
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
                    startFailure = e.getMessage();
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
            if (startFailure != null) {
                statusText.setText("Failed to start 启动失败: " + startFailure);
            } else {
                statusText.setText(failureSeen
                        ? "FAILED 测试失败，请查看日志 / see log below"
                        : "PASSED 测试完成，未发现错误 / no errors detected");
            }
        }
    }
}
