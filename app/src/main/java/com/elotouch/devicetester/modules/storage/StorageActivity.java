package com.elotouch.devicetester.modules.storage;

import android.app.usage.StorageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.text.format.Formatter;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;

/**
 * Storage module (PRD §3.2): internal storage space info, a non-destructive
 * sequential read/write speed test on a temp file in the app sandbox that is
 * always deleted afterwards, and a Full Fill test that writes into the app's
 * own cache until the storage is full (removed by the Clear button).
 */
public class StorageActivity extends BaseTestActivity {

    private static final int FILE_MB = 500;

    private static final String FILL_FILE_NAME = "elo_full_fill.tmp";
    private static final int FILL_CHUNK = 1024 * 1024; // 1MB per write
    private static final long UI_INTERVAL_NS = 500_000_000L;

    private TextView infoText;
    private TextView speedText;
    private TextView fillText;

    /** True while the Full Fill worker is writing. Guards against double start. */
    private volatile boolean filling = false;

    @Override
    protected String title() {
        return "Storage 存储测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Storage Space / 存储空间");
        infoText = addInfo("");
        refreshInfo();
        addButton("Refresh Info / 刷新存储信息", this::refreshInfo);

        addSectionTitle("Settings / 设置");
        addButton("Open Storage settings 打开存储设置", this::openStorageSettings);

        addSectionTitle("Read/Write Speed (" + FILE_MB + "MB, auto-deleted) / 读写速度（"
                + FILE_MB + "MB，测试后自动销毁）");
        speedText = addInfo("Tap to start. 点击开始测试。");
        addButton("Start R/W Test / 开始读写测试", this::runSpeed);

        addSectionTitle("Full Fill / 写满存储");
        fillText = addInfo("Writes a file into the app cache until the storage is full. "
                + "Tap Clear to delete it and free the space.\n"
                + "持续写入文件直至存储写满，点击清除可删除文件并释放空间。");
        addButton("Start Full Fill 开始写满", this::startFullFill);
        addButton("Stop 停止", this::stopFullFill);
        addButton("Clear 清除（删除写入文件）", this::clearFullFill);
    }

    /**
     * Opens Settings > Storage. Tries the action first, then the AOSP
     * StorageDashboardActivity by explicit component, since some builds
     * do not register the action.
     */
    private void openStorageSettings() {
        Intent[] candidates = {
                new Intent("android.settings.INTERNAL_STORAGE_SETTINGS"),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$StorageDashboardActivity"),
        };
        for (Intent intent : candidates) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Try the next candidate.
            }
        }
        toast("Storage settings not available. 无法打开存储设置。");
    }

    private void refreshInfo() {
        long total;
        long avail;
        try {
            // Same source as Settings > Storage, so the numbers match the system UI.
            StorageStatsManager ssm = getSystemService(StorageStatsManager.class);
            total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT);
            avail = ssm.getFreeBytes(StorageManager.UUID_DEFAULT);
        } catch (Exception e) {
            StatFs stat = new StatFs(getFilesDir().getAbsolutePath());
            total = stat.getBlockCountLong() * stat.getBlockSizeLong();
            avail = stat.getAvailableBytes();
        }
        long used = total - avail;
        infoText.setText(String.format(Locale.US,
                "Total 总容量：%s\nUsed 已用空间：%s\nAvailable 可用空间：%s",
                Formatter.formatFileSize(this, total),
                Formatter.formatFileSize(this, used),
                Formatter.formatFileSize(this, avail)));
    }

    private void runSpeed() {
        speedText.setText("Testing… 测试中…");
        runAsync(() -> {
            File tmp = new File(getCacheDir(), "elo_storage_test.tmp");
            final int n = FILE_MB * 1024 * 1024;
            byte[] buf = new byte[1024 * 1024]; // 1MB buffer
            new Random(42).nextBytes(buf);
            try {
                // ---- write ----
                long t0 = System.nanoTime();
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    int written = 0;
                    while (written < n && !isStopped()) {
                        fos.write(buf);
                        written += buf.length;
                    }
                    fos.flush();
                    fos.getFD().sync();
                }
                long writeNs = System.nanoTime() - t0;

                // ---- read ----
                t0 = System.nanoTime();
                long read = 0;
                try (FileInputStream fis = new FileInputStream(tmp)) {
                    int r;
                    while ((r = fis.read(buf)) != -1 && !isStopped()) read += r;
                }
                long readNs = System.nanoTime() - t0;

                double mb = FILE_MB;
                double writeSpeed = mb / (writeNs / 1e9);
                double readSpeed = mb / (readNs / 1e9);
                ui(() -> speedText.setText(String.format(Locale.US,
                        "Sequential write 顺序写入：%.1f MB/s\nSequential read 顺序读取：%.1f MB/s",
                        writeSpeed, readSpeed)));
            } catch (Exception e) {
                ui(() -> speedText.setText("Test failed 测试失败：" + e.getMessage()));
            } finally {
                // Non-destructive: always remove the temp file.
                if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
            }
        });
    }

    // ----------------------------------------------------------------- Full Fill

    private File fillFile() {
        return new File(getCacheDir(), FILL_FILE_NAME);
    }

    /** Free bytes on the volume holding the app cache (the volume being filled). */
    private long freeBytes() {
        StatFs stat = new StatFs(getCacheDir().getAbsolutePath());
        return stat.getAvailableBytes();
    }

    /**
     * Writes 1MB chunks into the app cache until the write fails with
     * "no space left" (storage full), or until Stop / Clear / leaving the page.
     * The file is kept after the fill so the user can inspect the result;
     * Clear deletes it.
     */
    private void startFullFill() {
        if (filling) {
            toast("Already filling. 正在写入中。");
            return;
        }
        filling = true;
        fillText.setText("Filling… 写入中…");
        runAsync(() -> {
            File f = fillFile();
            // Random data so storage/controller compression cannot shrink the writes.
            byte[] buf = new byte[FILL_CHUNK];
            new Random(7).nextBytes(buf);

            long written = 0;
            boolean full = false;
            long t0 = System.nanoTime();
            long lastUi = t0;
            try (FileOutputStream fos = new FileOutputStream(f)) {
                while (!isStopped()) {
                    try {
                        fos.write(buf);
                    } catch (IOException e) {
                        // ENOSPC: the storage is full. This is the expected end of the test.
                        full = true;
                        break;
                    }
                    written += buf.length;

                    long now = System.nanoTime();
                    if (now - lastUi >= UI_INTERVAL_NS) {
                        lastUi = now;
                        final long w = written;
                        final long free = freeBytes();
                        ui(() -> fillText.setText(progressText(w, free)));
                    }
                }
                if (!full) {
                    try {
                        fos.flush();
                        fos.getFD().sync();
                    } catch (IOException e) {
                        full = true; // a flush that cannot complete is also a full disk
                    }
                }
                double secs = (System.nanoTime() - t0) / 1e9;
                showFillResult(fillSummary(full, written, secs));
            } catch (IOException e) {
                showFillResult("Failed 失败：" + e.getMessage());
            } finally {
                filling = false;
            }
        });
    }

    private String fillSummary(boolean full, long written, double secs) {
        String size = Formatter.formatFileSize(this, written);
        if (full) {
            return String.format(Locale.US,
                    "Storage full 已写满：%s，用时 %.1f s。\n点击 Clear 清除以释放空间。\n"
                            + "Click Clear to free the space.", size, secs);
        }
        return String.format(Locale.US,
                "Stopped 已停止：已写入 %s，用时 %.1f s。\n点击 Clear 清除以释放空间。\n"
                        + "Click Clear to free the space.", size, secs);
    }

    private void showFillResult(String message) {
        ui(() -> {
            fillText.setText(message);
            refreshInfo();
        });
    }

    private String progressText(long written, long free) {
        return String.format(Locale.US,
                "Written 已写入：%s\nFree 剩余空间：%s\n"
                        + "Tap Stop to stop, Clear to delete the file.\n"
                        + "点击停止可结束写入，点击清除可删除文件。",
                Formatter.formatFileSize(this, written),
                Formatter.formatFileSize(this, free));
    }

    private void stopFullFill() {
        if (!filling) {
            toast("Not filling. 当前未在写入。");
            return;
        }
        stopTests();
    }

    /**
     * Stops any running fill, then deletes the fill file. Runs on the
     * single-thread executor so it executes only after the fill worker has
     * finished and closed the file.
     */
    private void clearFullFill() {
        stopTests();
        fillText.setText("Clearing… 清除中…");
        executor.execute(() -> {
            File f = fillFile();
            boolean ok = !f.exists() || f.delete();
            ui(() -> {
                fillText.setText(ok
                        ? "Cleared 已清除写入文件。\nCleared the fill file."
                        : "Delete failed 删除失败，请重试。");
                refreshInfo();
            });
        });
    }

    @Override
    protected void onDestroy() {
        // Leaving the page must not leave a full disk behind. Stop the fill and
        // delete the file off the UI thread. The executor is about to be shut
        // down, so the delete cannot be queued on it.
        stopTests();
        new Thread(() -> fillFile().delete(), "elo-fill-cleanup").start();
        super.onDestroy();
    }
}
