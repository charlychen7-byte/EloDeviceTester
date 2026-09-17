package com.elotouch.devicetester.modules.stability;

import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EMMC read/write task: writes a temp file of pseudo-random data in 1MB
 * chunks, fsyncs it, reads it back comparing every chunk, deletes it and
 * repeats with fresh data.
 *
 * <p>Non-destructive (PRD §5.6): the file only ever lives in the app's cache
 * directory and is deleted in a {@code finally}, including when the test is
 * stopped mid-write. The size shrinks to fit if free space is tight, and the
 * task reports itself skipped rather than filling the device.
 */
final class EmmcLoadTask implements StressTask {

    private static final int PREFERRED_MB = 256;
    private static final int MIN_MB = 16;
    private static final int BUFFER = 1024 * 1024;

    private final File cacheDir;
    private final AtomicInteger errors = new AtomicInteger();

    private volatile boolean running;
    private volatile int fileMb;
    private volatile int loops;
    private volatile double writeMbps;
    private volatile double readMbps;
    private volatile long mismatchedChunks;
    private volatile String note;

    EmmcLoadTask(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    @Override
    public String label() {
        return "EMMC";
    }

    @Override
    public void start(ExecutorService pool) {
        running = true;
        pool.execute(this::work);
    }

    @Override
    public void stop() {
        running = false;
    }

    private void work() {
        int sizeMb = chooseSizeMb();
        if (sizeMb < MIN_MB) {
            note = "可用空间不足，已跳过 / not enough free space, skipped";
            return;
        }
        fileMb = sizeMb;

        File file = new File(cacheDir, "elo_stability_emmc.tmp");
        byte[] writeBuffer = new byte[BUFFER];
        byte[] readBuffer = new byte[BUFFER];
        Random random = new Random();
        try {
            int pass = 0;
            while (running) {
                // Fresh data every pass so the test cannot be satisfied by a cache
                // that still holds the previous pattern.
                random.nextBytes(writeBuffer);

                long t0 = System.nanoTime();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    for (int i = 0; i < sizeMb && running; i++) {
                        out.write(writeBuffer);
                    }
                    out.flush();
                    out.getFD().sync();
                }
                if (!running) return;
                writeMbps = sizeMb / ((System.nanoTime() - t0) / 1e9);

                t0 = System.nanoTime();
                long mismatches = 0;
                try (FileInputStream in = new FileInputStream(file)) {
                    for (int i = 0; i < sizeMb && running; i++) {
                        if (!readFully(in, readBuffer)) {
                            mismatches++;
                            break;
                        }
                        if (!Arrays.equals(readBuffer, writeBuffer)) mismatches++;
                    }
                }
                if (!running) return;
                readMbps = sizeMb / ((System.nanoTime() - t0) / 1e9);

                if (mismatches > 0) {
                    mismatchedChunks += mismatches;
                    errors.incrementAndGet();
                }
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                loops = ++pass;
            }
        } catch (IOException e) {
            note = "读写失败 / I/O failed: " + e.getMessage();
            errors.incrementAndGet();
        } finally {
            // Never leave the temp file behind, however the loop ended.
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** Fills {@code buffer} completely; false if the file ended early. */
    private static boolean readFully(FileInputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) return false;
            offset += read;
        }
        return true;
    }

    /** Largest test size that leaves at least as much free space as it uses. */
    private int chooseSizeMb() {
        try {
            StatFs stat = new StatFs(cacheDir.getAbsolutePath());
            long availableMb = (stat.getAvailableBlocksLong() * stat.getBlockSizeLong())
                    / (1024 * 1024);
            return (int) Math.min(PREFERRED_MB, availableMb / 2);
        } catch (Exception e) {
            note = "无法读取可用空间 / cannot read free space: " + e.getMessage();
            return 0;
        }
    }

    @Override
    public String status() {
        StringBuilder sb = new StringBuilder();
        if (fileMb > 0) {
            sb.append(String.format(Locale.US,
                    "%dMB 循环 file   写 Write %s   读 Read %s   循环 Loops %d   错误 Errors %d",
                    fileMb, speed(writeMbps), speed(readMbps), loops, errors.get()));
        } else {
            sb.append(String.format(Locale.US, "错误 Errors %d", errors.get()));
        }
        if (mismatchedChunks > 0) {
            sb.append(String.format(Locale.US, "   校验不一致 Bad chunks %d", mismatchedChunks));
        }
        String n = note;
        if (n != null) sb.append("\n     ").append(n);
        return sb.toString();
    }

    private static String speed(double mbps) {
        return mbps > 0 ? String.format(Locale.US, "%.1f MB/s", mbps) : "—";
    }

    @Override
    public int errorCount() {
        return errors.get();
    }
}
