package com.elotouch.devicetester.modules.reboot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * One reboot cycle's outcome: how long the boot took, what the self-checks
 * saw, and whether that counts as a pass.
 *
 * <p>Immutable, and cheap to serialise to a single line — the Activity only
 * ever shows the previous and current cycle, so those two are the only ones
 * kept in {@link RebootStressState}; the full history lives in the CSV log.
 */
public final class RebootCycle {

    /** Field separator for {@link #serialize()}; stripped out of the note. */
    private static final char SEP = '|';

    public final int index;
    /** Wall clock when the cycle was recorded, or 0 if never recorded. */
    public final long timestampMs;
    /** Time from power-on to BOOT_COMPLETED, or -1 if unknown. */
    public final long bootElapsedMs;
    /** Wall clock from the reboot trigger to this boot, or -1 if unknown. */
    public final long gapMs;
    public final boolean pass;
    public final Map<RebootChecks.Check, String> checks;
    public final String note;

    public RebootCycle(int index, long timestampMs, long bootElapsedMs, long gapMs,
                       boolean pass, Map<RebootChecks.Check, String> checks, String note) {
        this.index = index;
        this.timestampMs = timestampMs;
        this.bootElapsedMs = bootElapsedMs;
        this.gapMs = gapMs;
        this.pass = pass;
        this.checks = new EnumMap<>(checks);
        this.note = note == null ? "" : note;
    }

    public String checkToken(RebootChecks.Check check) {
        String token = checks.get(check);
        return token == null ? RebootChecks.SKIPPED : token;
    }

    // ------------------------------------------------------------ persistence

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(SEP)
                .append(timestampMs).append(SEP)
                .append(bootElapsedMs).append(SEP)
                .append(gapMs).append(SEP)
                .append(pass);
        for (RebootChecks.Check c : RebootChecks.Check.values()) {
            sb.append(SEP).append(checkToken(c));
        }
        sb.append(SEP).append(note.replace(SEP, '/'));
        return sb.toString();
    }

    /** Inverse of {@link #serialize()}; null if the string is absent or stale. */
    public static RebootCycle parse(String line) {
        if (line == null || line.isEmpty()) return null;
        int fixedFields = 5;
        int checkCount = RebootChecks.Check.values().length;
        // -1 keeps a trailing empty note as a field instead of dropping it.
        String[] parts = line.split("\\" + SEP, -1);
        if (parts.length != fixedFields + checkCount + 1) return null;
        try {
            Map<RebootChecks.Check, String> checks = new EnumMap<>(RebootChecks.Check.class);
            RebootChecks.Check[] all = RebootChecks.Check.values();
            for (int i = 0; i < checkCount; i++) {
                checks.put(all[i], parts[fixedFields + i]);
            }
            return new RebootCycle(
                    Integer.parseInt(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]),
                    Boolean.parseBoolean(parts[4]),
                    checks,
                    parts[fixedFields + checkCount]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // -------------------------------------------------------------- rendering

    /** e.g. {@code "#12   开机 38.2s   09-17 14:03:11   FAIL"}. */
    public String headline() {
        SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        return String.format(Locale.US, "#%d   开机 %s   %s   %s",
                index,
                bootElapsedMs < 0 ? "—" : String.format(Locale.US, "%.1fs", bootElapsedMs / 1000.0),
                timestampMs <= 0 ? "—" : stamp.format(new Date(timestampMs)),
                pass ? "PASS" : "FAIL");
    }

    /** e.g. {@code "ETH ✓   WIFI ✓   CD ✓   USB ✗"}, skipped checks omitted. */
    public String checkLine() {
        StringBuilder sb = new StringBuilder();
        for (RebootChecks.Check c : RebootChecks.Check.values()) {
            String token = checkToken(c);
            if (RebootChecks.SKIPPED.equals(token)) continue;
            if (sb.length() > 0) sb.append("   ");
            sb.append(shortName(c)).append(' ')
                    .append(RebootChecks.CONNECTED.equals(token) ? "✓" : "✗");
        }
        return sb.length() == 0 ? "（未启用自检项 / no checks enabled）" : sb.toString();
    }

    private static String shortName(RebootChecks.Check check) {
        switch (check) {
            case ETHERNET: return "ETH";
            case WIFI: return "WIFI";
            case CASH_DRAWER: return "CD";
            case USB_DRIVE: return "USB";
            default: return check.key;
        }
    }
}
