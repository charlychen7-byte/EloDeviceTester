package com.elotouch.devicetester.modules.stability;

import java.util.concurrent.ExecutorService;

/**
 * One continuously running subsystem load, hosted by {@link StabilityActivity}.
 *
 * <p>A task is created fresh each time Start is pressed, does its work on the
 * activity's shared pool (or its own render thread, for GPU), and publishes
 * live counters that the UI thread reads once a second via {@link #status()} —
 * tasks never touch views themselves.
 */
interface StressTask {

    /** Short fixed-width label for the status line, e.g. "CPU". */
    String label();

    /** Begins work. Must not block; called on the UI thread. */
    void start(ExecutorService pool);

    /**
     * Signals every worker to finish and releases what the task holds.
     * Called on the UI thread; must be safe to call more than once.
     */
    void stop();

    /** One-line live status, read from the UI thread. */
    String status();

    /**
     * Verification failures / driver errors seen so far. Sticky: once a task
     * has seen an error the run is a failure even if later passes are clean.
     */
    int errorCount();
}
