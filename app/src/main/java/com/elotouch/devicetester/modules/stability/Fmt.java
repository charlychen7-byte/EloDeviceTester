package com.elotouch.devicetester.modules.stability;

import java.util.Locale;

/** Compact number formatting shared by the stability tasks' status lines. */
final class Fmt {

    private Fmt() {}

    /** Iteration/loop counters: 1.42G, 3.10M, 512. */
    static String count(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2fG", value / 1_000_000_000.0);
        }
        if (value >= 1_000_000L) {
            return String.format(Locale.US, "%.2fM", value / 1_000_000.0);
        }
        if (value >= 1_000L) {
            return String.format(Locale.US, "%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    /** Byte totals from a MB figure: 1.20 TB, 3.40 GB, 512 MB. */
    static String mb(double megabytes) {
        if (megabytes >= 1024.0 * 1024.0) {
            return String.format(Locale.US, "%.2f TB", megabytes / (1024.0 * 1024.0));
        }
        if (megabytes >= 1024.0) {
            return String.format(Locale.US, "%.2f GB", megabytes / 1024.0);
        }
        return String.format(Locale.US, "%.0f MB", megabytes);
    }
}
