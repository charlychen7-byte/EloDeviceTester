package com.elotouch.devicetester.modules.cpu;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the CPU to a user-chosen utilisation target and releases it on demand.
 *
 * <p>One busy thread per core duty-cycles a {@value #PERIOD_MS} ms window: it
 * spins for {@code duty × period} and sleeps for the rest, so a target of 40%
 * really idles the cores 60% of the time instead of pegging them. A controller
 * thread measures the actual system load once a second and nudges the duty
 * cycle toward the target, which compensates for whatever else the device is
 * already doing — asking for 50% on a device that is idling at 15% settles the
 * busy threads near 35%, not 50% on top.
 *
 * <p>Threads are plain daemon threads, not the activity's single-thread
 * executor: that executor is shared with the 1 Hz info refresh and could only
 * ever load one core.
 *
 * <p>{@link #stop()} flips a flag and bumps a generation counter rather than
 * joining, so it is safe to call from {@code onPause} on the UI thread. Workers
 * check the generation inside their spin chunk and exit within microseconds; a
 * restart never adopts the previous run's threads because their generation is
 * already stale.
 */
public final class CpuLoadTester {

    /** Reports the measured load and the duty cycle currently applied. */
    public interface Listener {
        /** Called on the controller thread ~1×/s. {@code measuredPercent} may be UNKNOWN. */
        void onSample(double measuredPercent, boolean systemWide, int dutyPercent);
    }

    private static final long PERIOD_MS = 100L;
    private static final long PERIOD_NS = PERIOD_MS * 1_000_000L;
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    /** Fraction of the measured error folded into the duty cycle each second. */
    private static final double GAIN = 0.5;
    /** Iterations of arithmetic between two clock checks inside the spin. */
    private static final int SPIN_CHUNK = 2_000;

    private final int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
    private final List<Thread> workers = new ArrayList<>();

    private volatile boolean running;
    private volatile int generation;
    private volatile double duty;
    private volatile int targetPercent;
    /** Consumes the spin result so the JIT cannot delete the busy loop. */
    @SuppressWarnings("unused")
    private volatile double sink;

    public int threadCount() {
        return threads;
    }

    public boolean isRunning() {
        return running;
    }

    public int targetPercent() {
        return targetPercent;
    }

    public int dutyPercent() {
        return (int) Math.round(duty * 100.0);
    }

    /** Starts loading every core toward {@code targetPercent} (1–100). No-op if already running. */
    public synchronized void start(int targetPercent, Listener listener) {
        if (running) return;
        this.targetPercent = Math.max(0, Math.min(100, targetPercent));
        this.duty = this.targetPercent / 100.0;
        final int gen = ++generation;
        running = true;

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> work(gen), "cpu-load-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        Thread controller = new Thread(() -> control(gen, listener), "cpu-load-control");
        controller.setDaemon(true);
        workers.add(controller);
        controller.start();
    }

    /** Stops every busy thread and releases the CPU. Safe to call when not running. */
    public synchronized void stop() {
        running = false;
        generation++;
        for (Thread t : workers) t.interrupt();
        workers.clear();
    }

    private void work(int gen) {
        while (alive(gen)) {
            long busyNs = (long) (PERIOD_NS * duty);
            if (busyNs > 0) {
                long deadline = System.nanoTime() + busyNs;
                double acc = 0.0;
                do {
                    for (int k = 1; k <= SPIN_CHUNK; k++) {
                        acc += Math.sqrt(k) * k;
                    }
                } while (System.nanoTime() < deadline && alive(gen));
                sink = acc;
            }
            long idleNs = PERIOD_NS - busyNs;
            if (idleNs > 0 && !sleepNs(idleNs)) return;
        }
    }

    private void control(int gen, Listener listener) {
        CpuUsageSampler sampler = new CpuUsageSampler();
        sampler.sample(); // prime the delta
        while (alive(gen)) {
            if (!sleepNs(SAMPLE_INTERVAL_MS * 1_000_000L)) return;
            if (!alive(gen)) return;

            double measured = sampler.sample();
            if (measured != CpuUsageSampler.UNKNOWN) {
                double error = (targetPercent - measured) / 100.0;
                duty = Math.max(0.0, Math.min(1.0, duty + GAIN * error));
            }
            if (listener != null) {
                listener.onSample(measured, sampler.isSystemWide(), dutyPercent());
            }
        }
    }

    private boolean alive(int gen) {
        return running && gen == generation;
    }

    /** Sleeps; returns false if interrupted (the thread should then unwind). */
    private static boolean sleepNs(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
