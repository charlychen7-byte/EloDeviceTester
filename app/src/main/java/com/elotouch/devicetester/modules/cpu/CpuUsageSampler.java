package com.elotouch.devicetester.modules.cpu;

import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Samples CPU utilisation as a percentage of the interval between two
 * successive {@link #sample()} calls.
 *
 * <p>Preferred source is the aggregate {@code cpu} line of {@code /proc/stat}
 * (system-wide, the number a load test is judged by). Some hardened builds deny
 * it; in that case the sampler falls back — permanently, so it does not retry a
 * denied file every second — to this process' own CPU time via
 * {@link Process#getElapsedCpuTime()} divided by wall time × core count.
 * {@link #isSystemWide()} says which source the last sample came from so the UI
 * can label the number honestly.
 *
 * <p>Instances are stateful (each keeps its own "previous" snapshot) and are not
 * thread-safe: use one per sampling loop.
 */
public final class CpuUsageSampler {

    /** Returned when no percentage can be derived yet (first call) or at all. */
    public static final double UNKNOWN = -1.0;

    private final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

    private boolean procStatUsable = true;
    private boolean systemWide = true;

    private long prevBusyJiffies = -1L;
    private long prevTotalJiffies;

    private long prevCpuMs = -1L;
    private long prevElapsedMs;

    /** Whether the last {@link #sample()} measured the whole system or only this app. */
    public boolean isSystemWide() {
        return systemWide;
    }

    /**
     * CPU usage in percent since the previous call, clamped to 0–100, or
     * {@link #UNKNOWN} on the first call / when no interval has elapsed.
     */
    public double sample() {
        if (procStatUsable) {
            long[] jiffies = readProcStat();
            if (jiffies != null) {
                systemWide = true;
                long busy = jiffies[0];
                long total = jiffies[1];
                double percent = UNKNOWN;
                if (prevBusyJiffies >= 0 && total > prevTotalJiffies) {
                    percent = 100.0 * (busy - prevBusyJiffies) / (total - prevTotalJiffies);
                }
                prevBusyJiffies = busy;
                prevTotalJiffies = total;
                return clamp(percent);
            }
            procStatUsable = false;
        }
        systemWide = false;
        return sampleSelf();
    }

    /** Fallback: this process' CPU time over wall time, spread across all cores. */
    private double sampleSelf() {
        long cpuMs = Process.getElapsedCpuTime();
        long elapsedMs = SystemClock.elapsedRealtime();
        double percent = UNKNOWN;
        if (prevCpuMs >= 0 && elapsedMs > prevElapsedMs) {
            percent = 100.0 * (cpuMs - prevCpuMs) / ((elapsedMs - prevElapsedMs) * (double) cores);
        }
        prevCpuMs = cpuMs;
        prevElapsedMs = elapsedMs;
        return clamp(percent);
    }

    /** {@code {busy, total}} jiffies from the aggregate line, or {@code null} if unreadable. */
    private static long[] readProcStat() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] parts = line.trim().split("\\s+");
            // cpu user nice system idle iowait irq softirq steal ...
            if (parts.length < 6) return null;
            long total = 0L;
            long idle = 0L;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
                if (i == 4 || i == 5) idle += v; // idle + iowait
            }
            return new long[]{total - idle, total};
        } catch (Exception e) {
            // Missing, denied by SELinux, or a layout we do not recognise.
            return null;
        }
    }

    private static double clamp(double percent) {
        if (percent == UNKNOWN) return UNKNOWN;
        return Math.max(0.0, Math.min(100.0, percent));
    }
}
