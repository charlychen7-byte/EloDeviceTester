package com.elotouch.devicetester.modules.storage;

import android.os.StatFs;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.Random;

/**
 * Storage module (PRD §3.2): internal storage space info, and a
 * non-destructive sequential read/write speed test on a temp file in the
 * app sandbox that is always deleted afterwards.
 */
public class StorageActivity extends BaseTestActivity {

    private static final int FILE_MB = 50;

    private TextView infoText;
    private TextView speedText;

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

        addSectionTitle("Read/Write Speed (" + FILE_MB + "MB, auto-deleted) / 读写速度（"
                + FILE_MB + "MB，测试后自动销毁）");
        speedText = addInfo("Tap to start. 点击开始测试。");
        addButton("Start R/W Test / 开始读写测试", this::runSpeed);
    }

    private void refreshInfo() {
        StatFs stat = new StatFs(getFilesDir().getAbsolutePath());
        long block = stat.getBlockSizeLong();
        long total = stat.getBlockCountLong() * block;
        long avail = stat.getAvailableBlocksLong() * block;
        long used = total - avail;
        infoText.setText(String.format(Locale.US,
                "Total 总容量：%s\nUsed 已用空间：%s\nAvailable 可用空间：%s",
                fmt(total), fmt(used), fmt(avail)));
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

    private static String fmt(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
    }
}
