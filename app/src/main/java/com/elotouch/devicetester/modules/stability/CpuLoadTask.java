package com.elotouch.devicetester.modules.stability;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU full-load task: one busy thread per core running a mixed
 * integer/floating-point kernel whose result is known in advance, so a wrong
 * answer (rather than just heat) is reported as an error.
 *
 * <p>Saturating every core makes the UI sluggish while this runs. That is the
 * point of a full-load test, not a defect.
 */
final class CpuLoadTask implements StressTask {

    private static final int INNER = 5_000;
    /** Expected integer checksum of one inner pass, computed once at class load. */
    private static final long EXPECTED_SUM;

    static {
        long sum = 0;
        for (int k = 1; k <= INNER; k++) sum += (long) k * k;
        EXPECTED_SUM = sum;
    }

    private final int threads;
    private final AtomicLong passes = new AtomicLong();
    private final AtomicInteger errors = new AtomicInteger();
    private volatile boolean running;

    CpuLoadTask() {
        threads = Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public String label() {
        return "CPU";
    }

    @Override
    public void start(ExecutorService pool) {
        running = true;
        for (int i = 0; i < threads; i++) {
            pool.execute(this::work);
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    private void work() {
        while (running) {
            long sum = 0;
            double f = 1.0;
            for (int k = 1; k <= INNER; k++) {
                sum += (long) k * k;
                f += Math.sqrt(k) * Math.sin(k);
            }
            // A CPU that miscomputes under load shows up here rather than as a
            // silent wrong result somewhere else.
            if (sum != EXPECTED_SUM || Double.isNaN(f) || Double.isInfinite(f)) {
                errors.incrementAndGet();
            }
            passes.incrementAndGet();
        }
    }

    @Override
    public String status() {
        return String.format(Locale.US, "%d 线程 threads   迭代 Passes %s   错误 Errors %d",
                threads, Fmt.count(passes.get()), errors.get());
    }

    @Override
    public int errorCount() {
        return errors.get();
    }
}
