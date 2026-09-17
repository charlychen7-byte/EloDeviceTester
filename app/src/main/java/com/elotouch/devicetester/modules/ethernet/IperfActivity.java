package com.elotouch.devicetester.modules.ethernet;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.NativeProcessRunner;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the bundled iperf3 client with a command line the operator types in
 * full (e.g. {@code -c 192.168.1.10 -t 30 -R}), repeated a chosen number of
 * times, and lists the bitrate each run achieved.
 *
 * <p>Runs sequentially on the {@link BaseTestActivity} background executor.
 * There is no Stop button — each run is bounded by iperf3's own {@code -t} —
 * but leaving the page cancels the test via {@link #onStopTests()}, which is
 * also what keeps a run from outliving the Activity.
 */
public class IperfActivity extends BaseTestActivity {

    private static final String SO_NAME = "libiperf3.so";
    /**
     * Without this iperf3's stdout is fully buffered (it is not a tty), so the
     * log stays empty until the process exits and dumps everything at once.
     */
    private static final String FORCE_FLUSH = "--forceflush";

    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");
    private static final int MAX_LOG_LINES = 300;
    private static final int MAX_RUNS = 999;

    /** The "942 Mbits/sec" field of an iperf3 summary line. */
    private static final Pattern RATE_PATTERN = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)bits/sec", Pattern.CASE_INSENSITIVE);

    private EditText commandInput;
    private EditText countInput;
    private Button startButton;
    private TextView statusText;
    private TextView resultsText;
    private TextView logText;
    private ScrollView logScroll;

    private final Deque<String> logLines = new ArrayDeque<>();
    private int defaultStatusColor;

    private final NativeProcessRunner runner = new NativeProcessRunner();

    private boolean running;
    private long startTimeMs;
    private int totalRuns;
    private volatile int currentRun;
    private volatile String startFailure;
    private volatile boolean errorSeen;
    /** Summary rates seen so far in the current run; the last one wins. */
    private volatile Rate runSenderRate;
    private volatile Rate runReceiverRate;

    private final List<String> runLabels = new ArrayList<>();
    private final List<Double> runMbps = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            statusText.setText(String.format(Locale.US,
                    "Running 运行中   第 %d/%d 次 Run %d/%d   已测试时间 Elapsed: %s",
                    currentRun, totalRuns, currentRun, totalRuns,
                    formatElapsed(elapsedSeconds())));
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected String title() {
        return "iperf3";
    }

    @Override
    protected void buildUi() {
        addInfo("Type the iperf3 command to run, e.g. \"-c 192.168.1.10 -t 30 -i 1 -R\" ");

        addSectionTitle("iperf3 指令 / Command");
        commandInput = new EditText(this);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        commandInput.setSingleLine(true);
        commandInput.setHint("-c 192.168.1.10 -t 30 -i 1 -R");
        addView(commandInput);

        addSectionTitle("测试次数 / Run Count");
        countInput = new EditText(this);
        countInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        countInput.setSingleLine(true);
        countInput.setText("1");
        countInput.setHint("Default 1 / 默认 1 次");
        addView(countInput);

        statusText = addInfo("Tap Start. 点击开始。");
        defaultStatusColor = statusText.getCurrentTextColor();
        startButton = addButton("Start / 开始", this::start);

        addSectionTitle("每次测试速率 / Per-run Bitrate");
        resultsText = addInfo("—");
        resultsText.setTypeface(Typeface.MONOSPACE);

        buildLogBox();

        if (!NativeProcessRunner.isArm64Supported()) {
            startButton.setEnabled(false);
            statusText.setText("Unsupported CPU architecture (requires arm64-v8a).\n"
                    + "当前设备架构不支持 arm64-v8a 原生工具。");
        }
    }

    private void buildLogBox() {
        logText = new TextView(this);
        logText.setTextSize(15);
        logText.setLineSpacing(dp(2), 1f);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackgroundColor(Color.BLACK);
        logText.setTextColor(Color.WHITE);

        logScroll = new ScrollView(this);
        logScroll.addView(logText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        lp.topMargin = dp(6);
        logScroll.setLayoutParams(lp);
        // The page itself is one big ScrollView (BaseTestActivity.onCreate()), so this
        // same-axis nested ScrollView needs to claim touch priority itself.
        logScroll.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        content.addView(logScroll);
    }

    // ----------------------------------------------------------------- control

    private void start() {
        if (running) return;

        List<String> args = parsedArgs();
        if (args.isEmpty()) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText("Enter an iperf3 command first. 请先输入 iperf3 指令。");
            return;
        }
        if (!args.contains(FORCE_FLUSH)) args.add(FORCE_FLUSH);

        totalRuns = parseRunCount();
        running = true;
        errorSeen = false;
        startFailure = null;
        currentRun = 1;
        runLabels.clear();
        runMbps.clear();
        logLines.clear();
        logText.setText("");
        resultsText.setText("—");
        statusText.setTextColor(defaultStatusColor);
        startButton.setEnabled(false);
        setInputsEnabled(false);
        startTimeMs = System.currentTimeMillis();
        main.post(tick);

        final String[] argv = args.toArray(new String[0]);
        runAsync(() -> runAll(argv));
    }

    /** Runs iperf3 back to back on the background executor. */
    private void runAll(String[] args) {
        for (int i = 1; i <= totalRuns && !isStopped(); i++) {
            currentRun = i;
            runSenderRate = null;
            runReceiverRate = null;
            if (totalRuns > 1) {
                appendLog(String.format(Locale.US, "=== Run %d/%d 第 %d 次 ===",
                        i, totalRuns, i));
            }
            try {
                runner.run(this, SO_NAME, args, this::onLine);
            } catch (IOException e) {
                startFailure = e.getMessage();
                break;
            }
            // The receiver line is the achieved throughput; sender is the fallback
            // when a run only reported the sending side.
            final int index = i;
            final Rate rate = runReceiverRate != null ? runReceiverRate : runSenderRate;
            ui(() -> recordRun(index, rate));
        }
        ui(this::onAllFinished);
    }

    private void onLine(String line) {
        String lower = line.toLowerCase(Locale.US);
        if (lower.contains("error")) errorSeen = true;
        // Only iperf3's final summary lines carry these tags; with -P the per-stream
        // lines come first and the [SUM] totals last, so last-wins picks the total.
        if (lower.contains("receiver")) {
            Rate r = parseRate(line);
            if (r != null) runReceiverRate = r;
        } else if (lower.contains("sender")) {
            Rate r = parseRate(line);
            if (r != null) runSenderRate = r;
        }
        appendLog(line);
    }

    private void recordRun(int index, Rate rate) {
        runLabels.add(String.format(Locale.US, "第 %d 次 Run %d: %s", index, index,
                rate != null ? rate.display : "no result 未取得速率"));
        runMbps.add(rate != null ? rate.mbps : Double.NaN);
        renderResults();
    }

    private void renderResults() {
        if (runLabels.isEmpty()) {
            resultsText.setText("—");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String label : runLabels) sb.append(label).append('\n');

        int valid = 0;
        double sum = 0;
        for (Double mbps : runMbps) {
            if (mbps != null && !Double.isNaN(mbps)) {
                sum += mbps;
                valid++;
            }
        }
        if (valid > 1) {
            sb.append("平均 Average: ").append(formatMbps(sum / valid));
        }
        resultsText.setText(sb.toString().trim());
    }

    private void onAllFinished() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        startButton.setEnabled(true);
        setInputsEnabled(true);

        String elapsed = formatElapsed(elapsedSeconds());
        int completed = runLabels.size();
        boolean missingRate = false;
        for (Double mbps : runMbps) {
            if (mbps == null || Double.isNaN(mbps)) missingRate = true;
        }

        if (startFailure != null) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText("Failed to start 启动失败: " + startFailure);
        } else if (completed == 0) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText("Stopped before any run finished. 测试在首次完成前已中止。");
        } else if (errorSeen) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败，请查看日志 / see log below，完成 %d/%d 次，已测试时间 %s",
                    completed, totalRuns, elapsed));
        } else if (completed < totalRuns) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText(String.format(Locale.US,
                    "Stopped 已中止：完成 %d/%d 次，已测试时间 %s",
                    completed, totalRuns, elapsed));
        } else if (missingRate) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败：部分测试未取得速率 / some runs reported no bitrate，"
                            + "已测试时间 %s", elapsed));
        } else {
            statusText.setTextColor(PASS_COLOR);
            statusText.setText(String.format(Locale.US,
                    "PASSED 测试完成：%d/%d 次，已测试时间 Elapsed %s",
                    completed, totalRuns, elapsed));
        }
    }

    @Override
    protected void onStopTests() {
        main.removeCallbacks(tick);
        runner.stop();
    }

    // ------------------------------------------------------------------ inputs

    private void setInputsEnabled(boolean enabled) {
        commandInput.setEnabled(enabled);
        countInput.setEnabled(enabled);
    }

    /** Run count from the input; blank or unparsable means one run. */
    private int parseRunCount() {
        String text = countInput.getText().toString().trim();
        if (text.isEmpty()) return 1;
        try {
            int value = Integer.parseInt(text);
            if (value < 1) return 1;
            return Math.min(value, MAX_RUNS);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** The typed command as argv, minus any leading program name. */
    private List<String> parsedArgs() {
        List<String> args = tokenize(commandInput.getText().toString());
        if (!args.isEmpty()) {
            String first = args.get(0);
            if (first.equals("iperf3") || first.endsWith("/iperf3")
                    || first.equals(SO_NAME)) {
                args.remove(0);
            }
        }
        return args;
    }

    /** Splits a command line on whitespace, honouring single and double quotes. */
    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) tokens.add(current.toString());
        return tokens;
    }

    // -------------------------------------------------------------------- misc

    private void appendLog(String line) {
        ui(() -> {
            if (logLines.size() >= MAX_LOG_LINES) logLines.removeFirst();
            logLines.addLast(line);
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) sb.append(l).append('\n');
            logText.setText(sb.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private long elapsedSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    private static String formatMbps(double mbps) {
        if (mbps >= 1000) return String.format(Locale.US, "%.2f Gbits/sec", mbps / 1000.0);
        if (mbps < 1) return String.format(Locale.US, "%.0f Kbits/sec", mbps * 1000.0);
        return String.format(Locale.US, "%.1f Mbits/sec", mbps);
    }

    /** Last bitrate on a summary line, normalised to Mbit/s for averaging. */
    private static Rate parseRate(String line) {
        Matcher m = RATE_PATTERN.matcher(line);
        Rate last = null;
        while (m.find()) {
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2).toUpperCase(Locale.US);
            double mbps;
            switch (unit) {
                case "K": mbps = value / 1000.0; break;
                case "M": mbps = value; break;
                case "G": mbps = value * 1000.0; break;
                case "T": mbps = value * 1_000_000.0; break;
                default: mbps = value / 1_000_000.0; break; // bare bits/sec
            }
            last = new Rate(mbps, m.group().trim());
        }
        return last;
    }

    private static final class Rate {
        final double mbps;
        /** As iperf3 printed it, e.g. "942 Mbits/sec". */
        final String display;

        Rate(double mbps, String display) {
            this.mbps = mbps;
            this.display = display;
        }
    }
}
