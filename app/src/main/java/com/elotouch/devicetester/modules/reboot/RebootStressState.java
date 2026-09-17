package com.elotouch.devicetester.modules.reboot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.EnumSet;
import java.util.Locale;

/**
 * The reboot stress run's state, in {@link SharedPreferences} because it has
 * to survive the reboots it is measuring.
 *
 * <p>Deliberately bounded in size: only the previous and current cycle are
 * kept, alongside running aggregates (count / pass / fail / boot-time min,
 * max and sum). The complete per-cycle history goes to the CSV log instead,
 * so a thousand-cycle run costs the same prefs footprint as a ten-cycle one.
 *
 * <p>{@link #pendingCycle()} is what makes a hang visible: it is written
 * <em>before</em> the reboot is triggered, so if the device never comes back
 * the log ends at cycle N-1 while prefs still say cycle N was expected.
 */
public final class RebootStressState {

    private static final String PREFS = "reboot_stress";

    private static final String KEY_RUNNING = "running";
    private static final String KEY_TARGET = "target";
    private static final String KEY_DWELL_SEC = "dwell_sec";
    private static final String KEY_BOOT_LIMIT_SEC = "boot_limit_sec";
    private static final String KEY_ENABLED_CHECKS = "enabled_checks";
    private static final String KEY_PENDING_CYCLE = "pending_cycle";
    private static final String KEY_REBOOT_TRIGGERED_MS = "reboot_triggered_ms";
    private static final String KEY_COMPLETED = "completed";
    private static final String KEY_PASS = "pass_count";
    private static final String KEY_FAIL = "fail_count";
    private static final String KEY_BOOT_MIN_MS = "boot_min_ms";
    private static final String KEY_BOOT_MAX_MS = "boot_max_ms";
    private static final String KEY_BOOT_SUM_MS = "boot_sum_ms";
    private static final String KEY_BOOT_SAMPLES = "boot_samples";
    private static final String KEY_LOG_PATH = "log_path";
    private static final String KEY_RUN_STARTED_MS = "run_started_ms";
    private static final String KEY_CURRENT_CYCLE = "current_cycle";
    private static final String KEY_PREVIOUS_CYCLE = "previous_cycle";
    private static final String KEY_LAST_ERROR = "last_error";

    public static final int DEFAULT_TARGET = 50;
    public static final int DEFAULT_DWELL_SEC = 60;
    public static final int DEFAULT_BOOT_LIMIT_SEC = 180;

    private final SharedPreferences prefs;

    public RebootStressState(Context context) {
        // Application context: this is read from a boot receiver and a service
        // as well as from the Activity.
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ config

    public boolean isRunning() { return prefs.getBoolean(KEY_RUNNING, false); }

    public int target() { return prefs.getInt(KEY_TARGET, DEFAULT_TARGET); }

    public int dwellSec() { return prefs.getInt(KEY_DWELL_SEC, DEFAULT_DWELL_SEC); }

    public int bootLimitSec() {
        return prefs.getInt(KEY_BOOT_LIMIT_SEC, DEFAULT_BOOT_LIMIT_SEC);
    }

    public EnumSet<RebootChecks.Check> enabledChecks() {
        EnumSet<RebootChecks.Check> set = EnumSet.noneOf(RebootChecks.Check.class);
        String joined = prefs.getString(KEY_ENABLED_CHECKS, "");
        if (joined == null || joined.isEmpty()) return set;
        for (String key : joined.split(",")) {
            for (RebootChecks.Check c : RebootChecks.Check.values()) {
                if (c.key.equals(key)) set.add(c);
            }
        }
        return set;
    }

    /**
     * Wipes the previous run and arms a new one. The caller supplies the log
     * path because the log preamble is written at the same moment.
     */
    public void startRun(int target, int dwellSec, int bootLimitSec,
                         EnumSet<RebootChecks.Check> checks, String logPath) {
        StringBuilder joined = new StringBuilder();
        for (RebootChecks.Check c : checks) {
            if (joined.length() > 0) joined.append(",");
            joined.append(c.key);
        }
        prefs.edit()
                .putBoolean(KEY_RUNNING, true)
                .putInt(KEY_TARGET, target)
                .putInt(KEY_DWELL_SEC, dwellSec)
                .putInt(KEY_BOOT_LIMIT_SEC, bootLimitSec)
                .putString(KEY_ENABLED_CHECKS, joined.toString())
                .putString(KEY_LOG_PATH, logPath)
                .putLong(KEY_RUN_STARTED_MS, System.currentTimeMillis())
                .putInt(KEY_PENDING_CYCLE, 0)
                .putLong(KEY_REBOOT_TRIGGERED_MS, 0L)
                .putInt(KEY_COMPLETED, 0)
                .putInt(KEY_PASS, 0)
                .putInt(KEY_FAIL, 0)
                .putLong(KEY_BOOT_MIN_MS, Long.MAX_VALUE)
                .putLong(KEY_BOOT_MAX_MS, 0L)
                .putLong(KEY_BOOT_SUM_MS, 0L)
                .putInt(KEY_BOOT_SAMPLES, 0)
                .remove(KEY_CURRENT_CYCLE)
                .remove(KEY_PREVIOUS_CYCLE)
                .remove(KEY_LAST_ERROR)
                .commit();
    }

    /**
     * Disarms the run so no further reboot is triggered. Uses a synchronous
     * commit: the very next thing that happens may be a reboot, and an
     * apply() racing a shutdown could leave the run armed.
     */
    public void stopRun() {
        prefs.edit().putBoolean(KEY_RUNNING, false).commit();
    }

    // ------------------------------------------------------------- run progress

    public int completed() { return prefs.getInt(KEY_COMPLETED, 0); }

    public int passCount() { return prefs.getInt(KEY_PASS, 0); }

    public int failCount() { return prefs.getInt(KEY_FAIL, 0); }

    public String logPath() { return prefs.getString(KEY_LOG_PATH, null); }

    public long runStartedMs() { return prefs.getLong(KEY_RUN_STARTED_MS, 0L); }

    public int pendingCycle() { return prefs.getInt(KEY_PENDING_CYCLE, 0); }

    public long rebootTriggeredMs() { return prefs.getLong(KEY_REBOOT_TRIGGERED_MS, 0L); }

    public String lastError() { return prefs.getString(KEY_LAST_ERROR, null); }

    public void setLastError(String message) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply();
    }

    /**
     * Records that cycle {@code n} is about to be attempted, together with the
     * wall clock at which the reboot was triggered. Committed synchronously
     * because the reboot follows immediately.
     */
    public void markRebootTriggered(int cycle) {
        prefs.edit()
                .putInt(KEY_PENDING_CYCLE, cycle)
                .putLong(KEY_REBOOT_TRIGGERED_MS, System.currentTimeMillis())
                .commit();
    }

    public RebootCycle currentCycle() {
        return RebootCycle.parse(prefs.getString(KEY_CURRENT_CYCLE, null));
    }

    public RebootCycle previousCycle() {
        return RebootCycle.parse(prefs.getString(KEY_PREVIOUS_CYCLE, null));
    }

    /** Shifts current to previous, stores the new cycle and folds in its stats. */
    public void recordCycle(RebootCycle cycle) {
        SharedPreferences.Editor editor = prefs.edit();
        String current = prefs.getString(KEY_CURRENT_CYCLE, null);
        if (current != null) editor.putString(KEY_PREVIOUS_CYCLE, current);
        editor.putString(KEY_CURRENT_CYCLE, cycle.serialize());
        editor.putInt(KEY_COMPLETED, completed() + 1);
        if (cycle.pass) editor.putInt(KEY_PASS, passCount() + 1);
        else editor.putInt(KEY_FAIL, failCount() + 1);
        if (cycle.bootElapsedMs >= 0) {
            editor.putLong(KEY_BOOT_MIN_MS, Math.min(bootMinMs(), cycle.bootElapsedMs));
            editor.putLong(KEY_BOOT_MAX_MS, Math.max(bootMaxMs(), cycle.bootElapsedMs));
            editor.putLong(KEY_BOOT_SUM_MS, bootSumMs() + cycle.bootElapsedMs);
            editor.putInt(KEY_BOOT_SAMPLES, bootSamples() + 1);
        }
        editor.commit();
    }

    private long bootMinMs() { return prefs.getLong(KEY_BOOT_MIN_MS, Long.MAX_VALUE); }

    private long bootMaxMs() { return prefs.getLong(KEY_BOOT_MAX_MS, 0L); }

    private long bootSumMs() { return prefs.getLong(KEY_BOOT_SUM_MS, 0L); }

    private int bootSamples() { return prefs.getInt(KEY_BOOT_SAMPLES, 0); }

    /** e.g. "已完成 12/50 · PASS 11 / FAIL 1 · 开机耗时 min 31.4 / avg 36.8 / max 42.1 s". */
    public String summaryLine() {
        StringBuilder sb = new StringBuilder(String.format(Locale.US,
                "已完成 %d/%d · PASS %d / FAIL %d",
                completed(), target(), passCount(), failCount()));
        int samples = bootSamples();
        if (samples > 0) {
            sb.append(String.format(Locale.US,
                    " · 开机耗时 min %.1f / avg %.1f / max %.1f s",
                    bootMinMs() / 1000.0,
                    bootSumMs() / 1000.0 / samples,
                    bootMaxMs() / 1000.0));
        }
        return sb.toString();
    }
}
