package com.elotouch.devicetester.modules.stability;

import android.app.ActivityManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DDR read/write task: fills a bounded set of heap blocks with an
 * index-derived pattern, reads every byte back and compares it, then repeats
 * with a new pattern.
 *
 * <p>Safety threshold (PRD §5.6): the working set is capped at both a quarter
 * of available RAM and 60% of the remaining Java heap — deliberately more
 * conservative than the standalone DDR module's 50%/70%, because here EMMC,
 * GPU and CPU load may be running at the same time. {@link OutOfMemoryError}
 * releases everything and marks the task degraded rather than killing the app.
 */
final class DdrLoadTask implements StressTask {

    private static final int CHUNK = 8 * 1024 * 1024;

    private final Context context;
    private final AtomicInteger errors = new AtomicInteger();

    private volatile boolean running;
    private volatile long allocatedBytes;
    private volatile long verifiedBytes;
    private volatile int loops;
    private volatile long mismatchedBytes;
    private volatile String note;

    DdrLoadTask(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String label() {
        return "DDR";
    }

    @Override
    public void start(ExecutorService pool) {
        running = true;
        pool.execute(this::work);
    }

    @Override
    public void stop() {
        // The worker owns the blocks and drops them on the way out, so there is
        // no shared list to race over.
        running = false;
    }

    private void work() {
        List<byte[]> blocks = new ArrayList<>();
        try {
            long target = targetBytes();
            try {
                while (running && allocatedBytes < target) {
                    byte[] block = new byte[CHUNK];
                    blocks.add(block);
                    allocatedBytes += CHUNK;
                }
            } catch (OutOfMemoryError oom) {
                // Give a block back so the verify loop below still has room to work.
                if (!blocks.isEmpty()) {
                    blocks.remove(blocks.size() - 1);
                    allocatedBytes -= CHUNK;
                }
                note = "接近内存上限，已降级 / near memory limit, reduced";
            }
            if (blocks.isEmpty()) {
                note = "无法分配内存 / could not allocate";
                return;
            }

            int pass = 0;
            while (running) {
                byte seed = (byte) pass;
                for (byte[] block : blocks) {
                    if (!running) return;
                    for (int i = 0; i < block.length; i++) {
                        block[i] = (byte) (i ^ seed);
                    }
                }
                long mismatches = 0;
                for (byte[] block : blocks) {
                    if (!running) return;
                    for (int i = 0; i < block.length; i++) {
                        if (block[i] != (byte) (i ^ seed)) mismatches++;
                    }
                }
                if (mismatches > 0) {
                    mismatchedBytes += mismatches;
                    errors.incrementAndGet();
                }
                verifiedBytes += allocatedBytes;
                loops = ++pass;
            }
        } catch (OutOfMemoryError oom) {
            note = "内存不足已释放 / released on OOM";
        } finally {
            blocks.clear();
        }
    }

    /** Working-set cap: min(25% of available RAM, 60% of what is left of the heap). */
    private long targetBytes() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        long availRam = Long.MAX_VALUE;
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            availRam = (long) (mi.availMem * 0.25);
        }
        Runtime rt = Runtime.getRuntime();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        long heapRoom = (long) (rt.maxMemory() * 0.60) - usedHeap;
        long target = Math.min(availRam, heapRoom);
        // Always try at least one chunk; the OOM path handles a device too tight for it.
        return Math.max(target, CHUNK);
    }

    @Override
    public String status() {
        StringBuilder sb = new StringBuilder(String.format(Locale.US,
                "工作集 Set %s   已校验 Verified %s   循环 Loops %d   错误 Errors %d",
                Fmt.mb(allocatedBytes / (1024.0 * 1024.0)),
                Fmt.mb(verifiedBytes / (1024.0 * 1024.0)),
                loops, errors.get()));
        if (mismatchedBytes > 0) {
            sb.append(String.format(Locale.US, "   不一致字节 Mismatched %d", mismatchedBytes));
        }
        String n = note;
        if (n != null) sb.append("\n     ").append(n);
        return sb.toString();
    }

    @Override
    public int errorCount() {
        return errors.get();
    }
}
