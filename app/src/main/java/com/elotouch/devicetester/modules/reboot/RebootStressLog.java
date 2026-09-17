package com.elotouch.devicetester.modules.reboot;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.Locale;

/**
 * The reboot stress run's process log: one CSV row per cycle, appended to the
 * same file across every reboot of the run.
 *
 * <p>Unlike the battery logger — which owns its file for the lifetime of one
 * service — this file outlives the process that created it, so the path is
 * kept in {@link RebootStressState} and reopened in append mode after each
 * boot. A Stop followed by a Start begins a fresh file, so one file is always
 * exactly one run.
 *
 * <p>The file goes to app-private external storage, matching
 * {@code BatteryLogService}, so no storage permission is involved and the
 * operator can pull it with {@code adb pull}.
 */
public final class RebootStressLog {

    private static final String CSV_HEADER =
            "cycle,timestamp,boot_elapsed_s,gap_s,verdict,"
                    + "ethernet,wifi,cash_drawer,usb_drive,note";

    private RebootStressLog() {}

    /**
     * Creates the log file for a new run and writes its preamble and header.
     *
     * @return the absolute path of the new file
     * @throws IOException if the file cannot be created or written
     */
    public static String create(Context context, int target, int dwellSec, int bootLimitSec,
                                EnumSet<RebootChecks.Check> checks) throws IOException {
        File dir = new File(externalOrInternalDir(context), "reboot");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "reboot_stress_" + stamp + ".csv");

        StringBuilder enabled = new StringBuilder();
        for (RebootChecks.Check c : checks) {
            if (enabled.length() > 0) enabled.append(" ");
            enabled.append(c.key);
        }
        if (enabled.length() == 0) enabled.append("none");

        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write("# EloDeviceTester Reboot Stress\n");
            writer.write(String.format(Locale.US, "# run_started=%s%n", wallClock(new Date())));
            writer.write(String.format(Locale.US, "# device=%s %s, api=%d, abi=%s%n",
                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
                    Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown"));
            writer.write(String.format(Locale.US,
                    "# target=%d, dwell=%ds, boot_limit=%ds, checks=%s%n",
                    target, dwellSec, bootLimitSec, enabled));
            writer.write(CSV_HEADER + "\n");
        }
        return file.getAbsolutePath();
    }

    /** Appends one cycle. Returns null on success, or a message on failure. */
    public static String append(String logPath, RebootCycle cycle) {
        if (logPath == null) return "日志路径缺失 / log path missing";
        try (FileWriter writer = new FileWriter(new File(logPath), true)) {
            writer.write(String.format(Locale.US, "%d,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    cycle.index,
                    cycle.timestampMs <= 0 ? "" : wallClock(new Date(cycle.timestampMs)),
                    seconds(cycle.bootElapsedMs),
                    seconds(cycle.gapMs),
                    cycle.pass ? "PASS" : "FAIL",
                    cycle.checkToken(RebootChecks.Check.ETHERNET),
                    cycle.checkToken(RebootChecks.Check.WIFI),
                    cycle.checkToken(RebootChecks.Check.CASH_DRAWER),
                    cycle.checkToken(RebootChecks.Check.USB_DRIVE),
                    csvEscape(cycle.note)));
            return null;
        } catch (IOException e) {
            return "写入失败 / write failed: " + e.getMessage();
        }
    }

    /** Appends a closing comment line when the run ends or is stopped. */
    public static void appendFooter(String logPath, String summary) {
        if (logPath == null) return;
        try (FileWriter writer = new FileWriter(new File(logPath), true)) {
            writer.write(String.format(Locale.US, "# run_ended=%s %s%n",
                    wallClock(new Date()), summary));
        } catch (IOException ignored) {
            // A missing footer must never take the run down; the rows are what
            // matter and they are already on disk.
        }
    }

    private static File externalOrInternalDir(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    private static String wallClock(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date);
    }

    private static String seconds(long millis) {
        return millis < 0 ? "" : String.format(Locale.US, "%.1f", millis / 1000.0);
    }

    /** Notes can carry commas, so quote them the way CSV expects. */
    private static String csvEscape(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"").replace('\n', ' ') + "\"";
    }
}
