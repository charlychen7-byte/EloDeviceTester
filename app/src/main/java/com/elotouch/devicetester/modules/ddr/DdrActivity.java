package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DDR / RAM module (PRD §3.1): capacity info, read-write bandwidth (relative
 * reference value), and a safe stress-fill test.
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
