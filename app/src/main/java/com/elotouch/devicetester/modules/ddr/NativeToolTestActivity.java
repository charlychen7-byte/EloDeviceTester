package com.elotouch.devicetester.modules.ddr;

import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.NativeProcessRunner;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Base Activity for a bundled native diagnostic tool test (memtester, QMESA):
 * renders a description, optional extra controls, a status/elapsed-time line,
 * Start/Stop buttons, and a rolling terminal-style log below them, then
 * launches the tool via {@link NativeProcessRunner} and reports a final
 * color-coded PASSED/FAILED status with elapsed time (hh:mm:ss) when stopped
 * or finished.
 */
public abstract class NativeToolTestActivity extends BaseTestActivity {

    private static final int MAX_DISPLAY_LINE_LENGTH = 200;
    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");

    /** Bundled executable name under jniLibs/arm64-v8a/, e.g. "libmemtester.so". */
    protected abstract String soName();

    /** Command-line arguments; computed fresh each time Start is pressed. */
    protected abstract String[] buildArgs();

    /** Substring marking a failure in the tool's output, e.g. "FAILURE" or "FAILED". */
    protected abstract String failureKeyword();

    /** Bilingual description shown at the top of the page. */
    protected abstract String description();

    /**
     * Extra controls (radio groups, inputs) inserted between the description
     * and the Start/Stop buttons. Default: none.
     */
    protected void buildExtraControls() {
    }

    /**
     * Optional test duration in milliseconds; null means run until manually
     * stopped (default). Read fresh each time Start is pressed.
     */
    protected Long testDurationMs() {
        return null;
    }

    /**
     * Called with false when Start is pressed (disable extra controls while
     * running) and true when the test finishes (re-enable them). Default: no-op.
     */
    protected void setExtraControlsEnabled(boolean enabled) {
    }

    /**
     * File-name prefix for a per-run log file, e.g. "memtester"; every output
     * line is appended and flushed as it arrives. Null (default) writes no file.
     */
    protected String logFilePrefix() {
        return null;
    }

    /** Called on the UI thread when a run starts, before the tool is launched. Default: no-op. */
    protected void onRunStarted() {
    }

    /** Called on the UI thread when a run ends, however it ended. Default: no-op. */
    protected void onRunFinished() {
    }

    private final NativeProcessRunner runner = new NativeProcessRunner();
    private final Deque<String> lastLines = new ArrayDeque<>();
    private final Object logBufferLock = new Object();
    private final List<String> pendingLines = new ArrayList<>();
    private boolean uiFlushScheduled;
    /** Only touched on the background run thread. */
    private BufferedWriter logWriter;

    private TextView statusText;
    private ScrollView logScroll;
    private TextView logText;
    private Button startButton;
    private Button stopButton;

    private boolean running;
    private volatile boolean failureSeen;
    private long startTimeMs;
    private String startFailure;
    private boolean userStopRequested;
    private int defaultStatusColor;

    private final Runnable autoStopRunnable = this::stop;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
            statusText.setText(String.format(Locale.US,
                    "%s  Elapsed 已测试时间: %s",
                    failureSeen ? "FAILURE detected 检测到失败" : "Running... 运行中",
                    formatElapsed(elapsed)));
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void buildUi() {
        addInfo(description());
        buildExtraControls();
        statusText = addInfo("Tap Start. 点击开始。");
        defaultStatusColor = statusText.getCurrentTextColor();
        startButton = addButton("Start / 开始", this::start);
        stopButton = addButton("Stop / 停止", this::stop);
        stopButton.setEnabled(false);
        logText = new TextView(this);
        logText.setTextSize(15);
        logText.setLineSpacing(dp(2), 1f);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackgroundColor(Color.BLACK);
        logText.setTextColor(Color.WHITE);

        logScroll = new ScrollView(this);
        logScroll.addView(logText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logScrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        logScrollParams.topMargin = dp(6);
        logScroll.setLayoutParams(logScrollParams);
        // The page itself is one big ScrollView (BaseTestActivity.onCreate()), so this
        // same-axis nested ScrollView needs to claim touch priority itself — otherwise
        // the outer page ScrollView intercepts the drag before the log box ever gets it.
        logScroll.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        content.addView(logScroll);

        if (!NativeProcessRunner.isArm64Supported()) {
            startButton.setEnabled(false);
            statusText.setText("Unsupported CPU architecture (requires arm64-v8a).\n"
                    + "当前设备架构不支持 arm64-v8a 原生工具。");
        }
    }

    private void start() {
        if (running) return;
        running = true;
        failureSeen = false;
        startFailure = null;
        userStopRequested = false;
        lastLines.clear();
        synchronized (logBufferLock) {
            pendingLines.clear();
            uiFlushScheduled = false;
        }
        startTimeMs = System.currentTimeMillis();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setExtraControlsEnabled(false);
        statusText.setTextColor(defaultStatusColor);
        logText.setText("");
        statusText.setText("Running... 运行中");
        main.post(tick);

        main.removeCallbacks(autoStopRunnable);
        Long durationMs = testDurationMs();
        if (durationMs != null) {
            main.postDelayed(autoStopRunnable, durationMs);
        }

        onRunStarted();

        String[] args = buildArgs();
        runAsync(() -> {
            openLogFile(args);
            try {
                runner.run(NativeToolTestActivity.this, soName(), args, this::onLine);
            } catch (IOException e) {
                startFailure = e.getMessage();
            } finally {
                closeLogFile();
            }
            ui(this::onFinished);
        });
    }

    /** Opens the per-run log file (background thread) if this tool asks for one. */
    private void openLogFile(String[] args) {
        String prefix = logFilePrefix();
        if (prefix == null) return;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File file = new File(dir, prefix + "_" + stamp + ".log");
        try {
            logWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8));
            onLine("=== " + prefix + " started " + stamp + " args " + Arrays.toString(args) + " ===");
            onLine("Log file 日志文件: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.w("NativeToolTest", "cannot open log file " + file, e);
            onLine("Log file unavailable 无法写入日志文件: " + e.getMessage());
        }
    }

    /** Writes the end-of-run marker and closes the log file (background thread). */
    private void closeLogFile() {
        if (logWriter == null) return;
        onLine("=== ended, exit code " + runner.lastExitCode() + " ===");
        try {
            logWriter.close();
        } catch (IOException e) {
            Log.w("NativeToolTest", "error closing log file", e);
        }
        logWriter = null;
    }

    /** Appends one line to the log file and flushes it so nothing is lost on a crash. */
    private void writeToLog(String line) {
        try {
            logWriter.write(line);
            logWriter.newLine();
            logWriter.flush();
        } catch (IOException e) {
            Log.w("NativeToolTest", "log write failed, stopping file log", e);
            logWriter = null;
        }
    }

    private void stop() {
        userStopRequested = true;
        runner.stop();
    }

    private void onLine(String line) {
        if (line.contains(failureKeyword())) failureSeen = true;
        if (logWriter != null) writeToLog(line);
        boolean shouldSchedule = false;
        synchronized (logBufferLock) {
            pendingLines.add(line);
            if (!uiFlushScheduled) {
                uiFlushScheduled = true;
                shouldSchedule = true;
            }
        }
        if (shouldSchedule) {
            ui(this::flushPendingLines);
        }
    }

    private void flushPendingLines() {
        List<String> toAppend;
        synchronized (logBufferLock) {
            toAppend = new ArrayList<>(pendingLines);
            pendingLines.clear();
            uiFlushScheduled = false;
        }
        for (String l : toAppend) {
            if (lastLines.size() >= 200) lastLines.removeFirst();
            lastLines.addLast(truncateForDisplay(l));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lastLines) sb.append(l).append('\n');
        logText.setText(sb.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String truncateForDisplay(String line) {
        if (line.length() <= MAX_DISPLAY_LINE_LENGTH) return line;
        return "…" + line.substring(line.length() - MAX_DISPLAY_LINE_LENGTH);
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    private void onFinished() {
        running = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        setExtraControlsEnabled(true);
        main.removeCallbacks(autoStopRunnable);
        onRunFinished();
        long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
        String elapsedStr = formatElapsed(elapsed);
        int exitCode = runner.lastExitCode();
        boolean killedBySignal = exitCode >= 129 && exitCode <= 192;
        if (startFailure != null) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText("Failed to start 启动失败: " + startFailure);
        } else if (killedBySignal && !userStopRequested) {
            int signal = exitCode - 128;
            statusText.setTextColor(defaultStatusColor);
            statusText.setText(String.format(Locale.US,
                    "Killed by OS (signal %d), elapsed %s — device sandbox policy may not "
                            + "support this binary.\n被系统终止（信号 %d），已测试时间 %s"
                            + "——设备沙箱策略可能不支持此二进制文件。",
                    signal, elapsedStr, signal, elapsedStr));
        } else if (failureSeen) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败，已测试时间 %s，请查看日志 / see log below, elapsed %s",
                    elapsedStr, elapsedStr));
        } else {
            statusText.setTextColor(PASS_COLOR);
            statusText.setText(String.format(Locale.US,
                    "PASSED 测试完成，已测试时间 %s / no errors detected, elapsed %s",
                    elapsedStr, elapsedStr));
        }
    }

    @Override
    protected void onStopTests() {
        userStopRequested = true;
        runner.stop();
    }
}
