package com.elotouch.devicetester.modules.ddr;

import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.NativeProcessRunner;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Base Activity for a bundled native diagnostic tool test (memtester, QMESA):
 * renders a description, a status/elapsed-time line, Start/Stop buttons, and
 * a rolling terminal-style log below them, then launches the tool via
 * {@link NativeProcessRunner} and reports a final PASSED/FAILED status with
 * elapsed time when stopped or finished.
 */
public abstract class NativeToolTestActivity extends BaseTestActivity {

    private static final int MAX_DISPLAY_LINE_LENGTH = 200;

    /** Bundled executable name under jniLibs/arm64-v8a/, e.g. "libmemtester.so". */
    protected abstract String soName();

    /** Command-line arguments; computed fresh each time Start is pressed. */
    protected abstract String[] buildArgs();

    /** Substring marking a failure in the tool's output, e.g. "FAILURE" or "FAILED". */
    protected abstract String failureKeyword();

    /** Bilingual description shown at the top of the page. */
    protected abstract String description();

    private final NativeProcessRunner runner = new NativeProcessRunner();
    private final Deque<String> lastLines = new ArrayDeque<>();
    private final Object logBufferLock = new Object();
    private final List<String> pendingLines = new ArrayList<>();
    private boolean uiFlushScheduled;

    private TextView statusText;
    private TextView logText;
    private Button startButton;
    private Button stopButton;

    private boolean running;
    private volatile boolean failureSeen;
    private long startTimeMs;
    private String startFailure;
    private boolean userStopRequested;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
            statusText.setText(String.format(Locale.US,
                    "%s  Elapsed 已测试时间: %02d:%02d",
                    failureSeen ? "FAILURE detected 检测到失败" : "Running... 运行中",
                    elapsed / 60, elapsed % 60));
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void buildUi() {
        addInfo(description());
        statusText = addInfo("Tap Start. 点击开始。");
        startButton = addButton("Start / 开始", this::start);
        stopButton = addButton("Stop / 停止", this::stop);
        stopButton.setEnabled(false);
        logText = addInfo("");
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackgroundColor(Color.BLACK);
        logText.setTextColor(Color.WHITE);

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
        logText.setText("");
        statusText.setText("Running... 运行中");
        main.post(tick);

        String[] args = buildArgs();
        runAsync(() -> {
            try {
                runner.run(NativeToolTestActivity.this, soName(), args, this::onLine);
            } catch (IOException e) {
                startFailure = e.getMessage();
            }
            ui(this::onFinished);
        });
    }

    private void stop() {
        userStopRequested = true;
        runner.stop();
    }

    private void onLine(String line) {
        if (line.contains(failureKeyword())) failureSeen = true;
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
            if (lastLines.size() >= 10) lastLines.removeFirst();
            lastLines.addLast(truncateForDisplay(l));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lastLines) sb.append(l).append('\n');
        logText.setText(sb.toString());
    }

    private static String truncateForDisplay(String line) {
        if (line.length() <= MAX_DISPLAY_LINE_LENGTH) return line;
        return "…" + line.substring(line.length() - MAX_DISPLAY_LINE_LENGTH);
    }

    private void onFinished() {
        running = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
        String elapsedStr = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60);
        int exitCode = runner.lastExitCode();
        boolean killedBySignal = exitCode >= 129 && exitCode <= 192;
        if (startFailure != null) {
            statusText.setText("Failed to start 启动失败: " + startFailure);
        } else if (killedBySignal && !userStopRequested) {
            int signal = exitCode - 128;
            statusText.setText(String.format(Locale.US,
                    "Killed by OS (signal %d), elapsed %s — device sandbox policy may not "
                            + "support this binary.\n被系统终止（信号 %d），已测试时间 %s"
                            + "——设备沙箱策略可能不支持此二进制文件。",
                    signal, elapsedStr, signal, elapsedStr));
        } else if (failureSeen) {
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败，已测试时间 %s，请查看日志 / see log below, elapsed %s",
                    elapsedStr, elapsedStr));
        } else {
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
