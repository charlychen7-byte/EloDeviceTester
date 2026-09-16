package com.elotouch.devicetester.modules.ping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot {@code /system/bin/ping} invocation, shared by the module page's
 * quick connectivity check and {@link PingTestActivity}'s continuous loop.
 *
 * <p>Always blocking — callers must run it off the UI thread.
 */
final class Ping {

    private static final Pattern TIME_PATTERN = Pattern.compile("time[=<]\\s*([0-9.]+)");
    private static final Pattern TTL_PATTERN =
            Pattern.compile("ttl=(\\d+)", Pattern.CASE_INSENSITIVE);

    private Ping() {}

    /** Sends a single ICMP echo and waits at most {@code timeoutSeconds} for the reply. */
    static Result once(String host, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/ping",
                    "-c", "1", "-W", String.valueOf(timeoutSeconds), host)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            String text = output.toString();

            String fatal = detectFatalError(text);
            if (fatal != null) return Result.fatal(fatal);
            if (exitCode != 0) return Result.failed(detectFailureReason(text));

            Matcher timeMatcher = TIME_PATTERN.matcher(text);
            double rtt = timeMatcher.find()
                    ? Double.parseDouble(timeMatcher.group(1)) : Double.NaN;
            Matcher ttlMatcher = TTL_PATTERN.matcher(text);
            String ttl = ttlMatcher.find() ? ttlMatcher.group(1) : null;
            return Result.reply(rtt, ttl);
        } catch (IOException e) {
            return Result.fatal("ping unavailable / 无法执行 ping: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed(null);
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Errors that make retrying pointless (the address itself is unusable). */
    private static String detectFatalError(String output) {
        String lower = output.toLowerCase(Locale.US);
        if (lower.contains("unknown host")
                || lower.contains("name or service not known")
                || lower.contains("temporary failure in name resolution")
                || lower.contains("bad address")) {
            return "cannot resolve host / 无法解析主机";
        }
        return null;
    }

    /**
     * Why a single packet failed, for the log. A downed link is deliberately
     * counted as a lost packet rather than ending a continuous test: observing
     * loss over time is the point, and a link can come back mid-run.
     */
    private static String detectFailureReason(String output) {
        String lower = output.toLowerCase(Locale.US);
        if (lower.contains("network is unreachable") || lower.contains("network unreachable")) {
            return "network unreachable / 网络不可达";
        }
        if (lower.contains("host unreachable") || lower.contains("destination host unreachable")) {
            return "host unreachable / 主机不可达";
        }
        return null;
    }

    static final class Result {
        final boolean received;
        /** Round-trip time in ms, or NaN when the reply carried no time field. */
        final double rttMs;
        final String ttl;
        /** Non-null: stop testing, the target is unusable. */
        final String fatalMessage;
        /** Why this one packet was lost; null means a plain timeout. */
        final String reason;

        private Result(boolean received, double rttMs, String ttl,
                       String fatalMessage, String reason) {
            this.received = received;
            this.rttMs = rttMs;
            this.ttl = ttl;
            this.fatalMessage = fatalMessage;
            this.reason = reason;
        }

        static Result reply(double rttMs, String ttl) {
            return new Result(true, rttMs, ttl, null, null);
        }

        static Result failed(String reason) {
            return new Result(false, Double.NaN, null, null, reason);
        }

        static Result fatal(String message) {
            return new Result(false, Double.NaN, null, message, null);
        }

        /** Human-readable loss cause, for logs and inline status. */
        String failureText() {
            if (fatalMessage != null) return fatalMessage;
            return reason != null ? reason : "request timeout / 请求超时";
        }
    }
}
