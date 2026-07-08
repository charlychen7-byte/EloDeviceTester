package com.elotouch.devicetester.core;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a native executable bundled under jniLibs/arm64-v8a/ (see
 * app/build.gradle's packaging.jniLibs.useLegacyPackaging), streaming its
 * combined stdout/stderr line by line. Used for third-party diagnostic
 * tools (memtester, QMESA) that ship as prebuilt ARM binaries rather than
 * Java code.
 */
public class NativeProcessRunner {

    public interface LineListener {
        void onLine(String line);
    }

    private volatile Process process;
    private volatile boolean stopRequested;
    private volatile int lastExitCode = Integer.MIN_VALUE;

    /** Exit code from the most recently completed {@link #run}, for diagnostics. */
    public int lastExitCode() {
        return lastExitCode;
    }

    public static boolean isArm64Supported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    /**
     * Launches {@code nativeLibraryDir/soName args...} and blocks, delivering
     * each output line to {@code onLine}, until the process exits or
     * {@link #stop()} kills it. Must be called off the UI thread.
     */
    public void run(Context context, String soName, String[] args, LineListener onLine)
            throws IOException {
        stopRequested = false;
        String exePath = context.getApplicationInfo().nativeLibraryDir + "/" + soName;
        List<String> command = new ArrayList<>();
        command.add(exePath);
        for (String a : args) command.add(a);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    if (stopRequested) break;
                    throw e;
                }
                if (line == null) break;
                onLine.onLine(line);
            }
        } finally {
            try {
                lastExitCode = process.waitFor();
                Log.i("NativeProcessRunner", exePath + " exited with code " + lastExitCode);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    /** Kills the running process, if any, unblocking a pending {@link #run}. */
    public void stop() {
        stopRequested = true;
        Process p = process;
        if (p != null) p.destroy();
    }
}
