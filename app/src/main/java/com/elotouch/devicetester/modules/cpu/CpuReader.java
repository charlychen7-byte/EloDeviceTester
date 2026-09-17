package com.elotouch.devicetester.modules.cpu;

import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads CPU facts from procfs/sysfs for the CPU module.
 *
 * <p>Every node touched here ({@code /proc/cpuinfo},
 * {@code /sys/devices/system/cpu/...}) is unreadable on some hardened builds,
 * so every read is guarded and simply yields {@code null} / {@link #UNKNOWN}
 * instead of throwing — the module then renders "not available" rather than
 * crashing (PRD §5.3 anti-crash rule applied to procfs).
 */
public final class CpuReader {

    /** Sentinel for a numeric value that could not be read. */
    public static final long UNKNOWN = -1L;

    private static final String CPU_DIR = "/sys/devices/system/cpu";

    private CpuReader() {}

    // --------------------------------------------------------------- topology

    /**
     * Number of CPUs the kernel knows about (including currently offline ones),
     * from {@code /sys/devices/system/cpu/present}, falling back to counting
     * {@code cpuN} directories and finally to {@link Runtime#availableProcessors()}
     * (which only counts online cores).
     */
    public static int coreCount() {
        int fromPresent = parsePresentRange(readFirstLine(CPU_DIR + "/present"));
        if (fromPresent > 0) return fromPresent;

        int dirs = 0;
        File[] children = new File(CPU_DIR).listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.getName().matches("cpu\\d+")) dirs++;
            }
        }
        if (dirs > 0) return dirs;
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Parses a cpu range list such as {@code "0-7"} or {@code "0,2-5"} into a count. */
    private static int parsePresentRange(String value) {
        if (value == null) return 0;
        int count = 0;
        for (String part : value.trim().split(",")) {
            if (part.isEmpty()) continue;
            try {
                int dash = part.indexOf('-');
                if (dash < 0) {
                    Integer.parseInt(part.trim());
                    count++;
                } else {
                    int from = Integer.parseInt(part.substring(0, dash).trim());
                    int to = Integer.parseInt(part.substring(dash + 1).trim());
                    if (to >= from) count += to - from + 1;
                }
            } catch (NumberFormatException ignored) {
                // Unparsable segment: skip it rather than lose the whole count.
            }
        }
        return count;
    }

    /** Best-effort SoC name: /proc/cpuinfo "Hardware", then Build SoC fields, then Build.HARDWARE. */
    public static String socName() {
        String hardware = cpuinfoValue("Hardware");
        if (hardware != null) return hardware;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String vendor = Build.SOC_MANUFACTURER;
            String model = Build.SOC_MODEL;
            boolean vendorOk = isUseful(vendor);
            boolean modelOk = isUseful(model);
            if (vendorOk && modelOk) return vendor + " " + model;
            if (modelOk) return model;
            if (vendorOk) return vendor;
        }
        return isUseful(Build.HARDWARE) ? Build.HARDWARE : null;
    }

    /** CPU core designation, e.g. the x86 "model name" or the ARM "Processor" line. */
    public static String processorName() {
        String modelName = cpuinfoValue("model name");
        if (modelName != null) return modelName;
        return cpuinfoValue("Processor");
    }

    /** Current scaling governor of a core, or {@code null}. */
    public static String governor(int cpu) {
        return readFirstLine(CPU_DIR + "/cpu" + cpu + "/cpufreq/scaling_governor");
    }

    private static boolean isUseful(String s) {
        return s != null && !s.isEmpty() && !"unknown".equalsIgnoreCase(s);
    }

    private static String cpuinfoValue(String key) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                if (line.substring(0, colon).trim().equalsIgnoreCase(key)) {
                    String value = line.substring(colon + 1).trim();
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {
            // /proc/cpuinfo unreadable or sanitized on this build.
        }
        return null;
    }

    // -------------------------------------------------------------- frequency

    /** Per-core frequency triple in kHz; any member may be {@link #UNKNOWN}. */
    public static final class CoreFreq {
        public final long curKHz;
        public final long minKHz;
        public final long maxKHz;

        CoreFreq(long curKHz, long minKHz, long maxKHz) {
            this.curKHz = curKHz;
            this.minKHz = minKHz;
            this.maxKHz = maxKHz;
        }
    }

    public static CoreFreq readFreq(int cpu) {
        String base = CPU_DIR + "/cpu" + cpu + "/cpufreq/";
        long cur = readLongOrUnknown(base + "scaling_cur_freq");
        if (cur == UNKNOWN) cur = readLongOrUnknown(base + "cpuinfo_cur_freq");
        long min = readLongOrUnknown(base + "cpuinfo_min_freq");
        if (min == UNKNOWN) min = readLongOrUnknown(base + "scaling_min_freq");
        long max = readLongOrUnknown(base + "cpuinfo_max_freq");
        if (max == UNKNOWN) max = readLongOrUnknown(base + "scaling_max_freq");
        return new CoreFreq(cur, min, max);
    }

    /**
     * Groups cores by max frequency into a cluster summary such as
     * {@code "4x1.80GHz + 3x2.42GHz + 1x2.84GHz"}, or {@code null} if no core
     * exposes a max frequency.
     */
    public static String clusterSummary(int coreCount) {
        List<Long> freqs = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i < coreCount; i++) {
            long max = readFreq(i).maxKHz;
            if (max == UNKNOWN) continue;
            any = true;
            int idx = freqs.indexOf(max);
            if (idx < 0) {
                freqs.add(max);
                counts.add(1);
            } else {
                counts.set(idx, counts.get(idx) + 1);
            }
        }
        if (!any) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < freqs.size(); i++) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(counts.get(i)).append('x')
                    .append(String.format(Locale.US, "%.2fGHz", freqs.get(i) / 1_000_000.0));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ files

    private static long readLongOrUnknown(String path) {
        String line = readFirstLine(path);
        if (line == null) return UNKNOWN;
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** First line of a file, or {@code null} if missing / permission denied. */
    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            if (line == null) return null;
            line = line.trim();
            return line.isEmpty() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }
}
